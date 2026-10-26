#!/usr/bin/env python3
"""Viora server: REST API + image hosting. Pure Python 3.8+ standard library, SQLite storage.
Run:  python3 server.py   (env: PORT=8000, DATABASE_URL=postgres://... or VIORA_DATA=./data for SQLite)
"""
import html as htmllib
import random
import base64, datetime, hashlib, hmac, io, json, math, os, re, secrets, shutil, sqlite3, subprocess, tempfile, threading, time, traceback, urllib.error, urllib.parse, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

# Storage: PostgreSQL when DATABASE_URL is set (e.g. Neon), otherwise a local SQLite file.
# Images are stored inside the database too, so the server itself keeps no files
# (works on hosts with an ephemeral filesystem such as Render's free tier).
DATABASE_URL = os.environ.get("DATABASE_URL")
PG = bool(DATABASE_URL)
if PG:
    import psycopg2, psycopg2.extras
DATA = os.environ.get("VIORA_DATA", "data")
DB_FILE = os.path.join(DATA, "viora.db")
MAX_BODY = 55 * 1024 * 1024        # request size cap (videos)
MAX_IMAGE_BYTES = 12 * 1024 * 1024
MAX_VIDEO_BYTES = 50 * 1024 * 1024
MAX_VIDEO_SECONDS = 15 * 60
STORY_TTL = 24 * 3600
if not PG: os.makedirs(DATA, exist_ok=True)

PK = "SERIAL PRIMARY KEY" if PG else "INTEGER PRIMARY KEY"
BLOB = "BYTEA" if PG else "BLOB"
SCHEMA = [
 f"CREATE TABLE IF NOT EXISTS users(id {PK}, username TEXT NOT NULL, salt TEXT NOT NULL, pw TEXT NOT NULL, bio TEXT NOT NULL DEFAULT '', avatar TEXT)",
 "CREATE UNIQUE INDEX IF NOT EXISTS users_name ON users(LOWER(username))",
 f"CREATE TABLE IF NOT EXISTS images(id {PK}, ctype TEXT NOT NULL, data {BLOB} NOT NULL)",
 "CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, created DOUBLE PRECISION)",
 f"CREATE TABLE IF NOT EXISTS posts(id {PK}, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, image TEXT NOT NULL, caption TEXT NOT NULL DEFAULT '', created DOUBLE PRECISION)",
 "CREATE TABLE IF NOT EXISTS likes(post_id INTEGER REFERENCES posts(id) ON DELETE CASCADE, user_id INTEGER REFERENCES users(id) ON DELETE CASCADE, PRIMARY KEY(post_id, user_id))",
 f"CREATE TABLE IF NOT EXISTS comments(id {PK}, post_id INTEGER NOT NULL REFERENCES posts(id) ON DELETE CASCADE, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, text TEXT NOT NULL, created DOUBLE PRECISION)",
 f"CREATE TABLE IF NOT EXISTS codes(id {PK}, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, kind TEXT NOT NULL, email TEXT NOT NULL, code_hash TEXT NOT NULL, expires DOUBLE PRECISION, created DOUBLE PRECISION, attempts INTEGER NOT NULL DEFAULT 0)",
 "CREATE TABLE IF NOT EXISTS follows(follower_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, followee_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, created DOUBLE PRECISION, PRIMARY KEY(follower_id, followee_id))",
 "CREATE TABLE IF NOT EXISTS post_views(post_id INTEGER NOT NULL REFERENCES posts(id) ON DELETE CASCADE, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, created DOUBLE PRECISION, PRIMARY KEY(post_id, user_id))",
 f"CREATE TABLE IF NOT EXISTS boosts(id {PK}, post_id INTEGER NOT NULL REFERENCES posts(id) ON DELETE CASCADE, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, days INTEGER NOT NULL, ends DOUBLE PRECISION NOT NULL, created DOUBLE PRECISION, session_id TEXT, views INTEGER NOT NULL DEFAULT 0, paused_at DOUBLE PRECISION)",
 f"CREATE TABLE IF NOT EXISTS messages(id {PK}, sender_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, recipient_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, text TEXT NOT NULL, created DOUBLE PRECISION, seen INTEGER NOT NULL DEFAULT 0)",
 "CREATE TABLE IF NOT EXISTS devices(token TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, created DOUBLE PRECISION)",
 f"CREATE TABLE IF NOT EXISTS notifications(id {PK}, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, actor_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, kind TEXT NOT NULL, post_id INTEGER REFERENCES posts(id) ON DELETE CASCADE, text TEXT, created DOUBLE PRECISION, seen INTEGER NOT NULL DEFAULT 0)",
 "CREATE INDEX IF NOT EXISTS notifications_user ON notifications(user_id, seen)",
 "CREATE TABLE IF NOT EXISTS follow_requests(requester_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, target_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, created DOUBLE PRECISION, PRIMARY KEY(requester_id, target_id))",
 "CREATE TABLE IF NOT EXISTS dm_cleared(user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, other_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, upto INTEGER NOT NULL, PRIMARY KEY(user_id, other_id))",
 "CREATE INDEX IF NOT EXISTS messages_recipient ON messages(recipient_id, sender_id, seen)",
 "CREATE INDEX IF NOT EXISTS messages_sender ON messages(sender_id, recipient_id)",
 f"CREATE TABLE IF NOT EXISTS stories(id {PK}, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, image TEXT NOT NULL, created DOUBLE PRECISION)",
]
INTEGRITY = (sqlite3.IntegrityError, psycopg2.IntegrityError) if PG else (sqlite3.IntegrityError,)

class DB:
    """Tiny wrapper so the same SQL ('?' placeholders) runs on SQLite and PostgreSQL."""
    def __enter__(self):
        if PG:
            self.c = psycopg2.connect(DATABASE_URL)
        else:
            self.c = sqlite3.connect(DB_FILE, timeout=10); self.c.row_factory = sqlite3.Row
            self.c.execute("PRAGMA foreign_keys=ON")
        return self
    def __exit__(self, et, ev, tb):
        try: self.c.commit() if et is None else self.c.rollback()
        finally: self.c.close()
    def execute(self, sql, args=()):
        if PG:
            cur = self.c.cursor(cursor_factory=psycopg2.extras.RealDictCursor)
            cur.execute(sql.replace("?", "%s"), args); return cur
        return self.c.execute(sql, args)

def db(): return DB()

with db() as _c:
    for _s in SCHEMA: _c.execute(_s)
for _col, _typ in (("email", "TEXT"), ("email_verified", "INTEGER NOT NULL DEFAULT 0"), ("is_vip", "INTEGER NOT NULL DEFAULT 0"), ("vip_until", "DOUBLE PRECISION"), ("stripe_customer", "TEXT"), ("play_token", "TEXT"), ("following_privacy", "TEXT NOT NULL DEFAULT 'everyone'"), ("is_private", "INTEGER NOT NULL DEFAULT 0")):
    try:  # add columns to databases created before email support existed
        with db() as _c: _c.execute(f"ALTER TABLE users ADD COLUMN {'IF NOT EXISTS ' if PG else ''}{_col} {_typ}")
    except Exception:
        pass
for _col, _typ in (("kind", "TEXT NOT NULL DEFAULT 'image'"), ("thumb", "TEXT")):
    try:  # video posts: kind + poster image
        with db() as _c: _c.execute(f"ALTER TABLE posts ADD COLUMN {'IF NOT EXISTS ' if PG else ''}{_col} {_typ}")
    except Exception:
        pass
try:  # boosts pause while the account is private
    with db() as _c: _c.execute(f"ALTER TABLE boosts ADD COLUMN {'IF NOT EXISTS ' if PG else ''}paused_at DOUBLE PRECISION")
except Exception:
    pass
try:  # boosts paused by an earlier version end at the moment they were paused
    with db() as _c: _c.execute("UPDATE boosts SET ends=paused_at, paused_at=NULL WHERE paused_at IS NOT NULL")
except Exception:
    pass
with db() as _c:  # an email can belong to only one account once verified
    _c.execute("CREATE UNIQUE INDEX IF NOT EXISTS users_email ON users(LOWER(email)) WHERE email_verified=1")

class HTTPError(Exception):
    def __init__(self, code, msg):
        self.code, self.msg = code, msg

def hash_pw(pw, salt_hex):
    return hashlib.pbkdf2_hmac("sha256", pw.encode(), bytes.fromhex(salt_hex), 200_000).hex()

def sniff(b):
    if b[:3] == b"\xff\xd8\xff": return "image/jpeg"
    if b[:8] == b"\x89PNG\r\n\x1a\n": return "image/png"
    if b[:4] == b"RIFF" and b[8:12] == b"WEBP": return "image/webp"
    if b[:4] == b"GIF8": return "image/gif"
    return None

def moderate_images(items):
    """items: list of (bytes, content_type). Blocks sexual content using OpenAI's free moderation endpoint
    (omni-moderation-latest supports images). Off unless OPENAI_API_KEY is set; if the check fails, refuse (fail closed)."""
    key = os.environ.get("OPENAI_API_KEY")
    if not key or not items: return
    url = os.environ.get("MODERATION_URL", "https://api.openai.com/v1/moderations")
    threshold = float(os.environ.get("MODERATION_THRESHOLD", "0.5"))
    body = json.dumps({"model": "omni-moderation-latest", "input": [
        {"type": "image_url", "image_url": {"url": f"data:{ct};base64,{base64.b64encode(d).decode()}"}} for d, ct in items]}).encode()
    req = urllib.request.Request(url, data=body, headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    try:
        results = json.loads(urllib.request.urlopen(req, timeout=40).read())["results"]
    except Exception:
        traceback.print_exc()
        raise HTTPError(503, "Couldn't check this file right now. Please try again in a moment.")
    for res in results:
        if res["categories"].get("sexual") or res["category_scores"].get("sexual", 0) >= threshold:
            print("moderation: blocked a file, sexual score", res["category_scores"].get("sexual"), flush=True)
            raise HTTPError(422, "This looks like it contains sexual content, so it can't be posted.")

def moderate_image(data, ctype):
    moderate_images([(data, ctype)])

# ---- video: frame sampling (PyAV if installed, else the ffmpeg command-line tools) ----
def _mp4_boxes(buf, start, end):
    i = start
    while i + 8 <= end:
        size = int.from_bytes(buf[i:i + 4], "big"); typ = buf[i + 4:i + 8]; hdr = 8
        if size == 1: size = int.from_bytes(buf[i + 8:i + 16], "big"); hdr = 16
        elif size == 0: size = end - i
        if size < hdr: break
        yield typ, i + hdr, min(i + size, end)
        i += size

def mp4_rotation(data):
    """Clockwise rotation (0/90/180/270) that players apply to the video track, read from the MP4 'tkhd' matrix.
    Phones record portrait video as landscape pixels plus this rotation; PyAV doesn't apply it, so we do."""
    try:
        for t, s, e in _mp4_boxes(data, 0, len(data)):
            if t != b"moov": continue
            for t2, s2, e2 in _mp4_boxes(data, s, e):
                if t2 != b"trak": continue
                for t3, s3, e3 in _mp4_boxes(data, s2, e2):
                    if t3 != b"tkhd": continue
                    off = s3 + 4 + (20 if data[s3] == 0 else 32) + 16
                    a, b, c, d = (int.from_bytes(data[off + k:off + k + 4], "big", signed=True) for k in (0, 4, 12, 16))
                    if int.from_bytes(data[off + 36:off + 40], "big") == 0: continue  # audio track
                    one = 0x10000
                    return {(0, one, -one, 0): 90, (-one, 0, 0, -one): 180, (0, -one, one, 0): 270}.get((a, b, c, d), 0)
    except Exception:
        pass
    return 0

def _frames_pyav(data, n):
    import av
    out = []
    rotation = mp4_rotation(data)
    with av.open(io.BytesIO(data)) as container:
        if not container.streams.video: raise ValueError("no video stream")
        duration = container.duration / av.time_base if container.duration else None
        if not duration: raise ValueError("unknown duration")
        for i in range(n):
            container.seek(int(duration * (i + 0.5) / n * av.time_base))
            for frame in container.decode(video=0):
                img = frame.to_image()
                if rotation: img = img.rotate(-rotation, expand=True)  # PIL angles are counter-clockwise
                img.thumbnail((640, 640))
                buf = io.BytesIO(); img.convert("RGB").save(buf, "JPEG", quality=80); out.append(buf.getvalue())
                break
    return out, duration

def _frames_ffmpeg(data, n):
    with tempfile.NamedTemporaryFile(suffix=".mp4") as f:
        f.write(data); f.flush()
        probe = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", f.name],
                               capture_output=True, text=True, timeout=20)
        duration = float(probe.stdout.strip())
        out = []
        for i in range(n):
            t = duration * (i + 0.5) / n
            r = subprocess.run(["ffmpeg", "-v", "error", "-ss", f"{t:.2f}", "-i", f.name, "-frames:v", "1",
                                "-vf", "scale='min(640,iw)':-2", "-f", "image2pipe", "-c:v", "mjpeg", "-q:v", "5", "pipe:1"],
                               capture_output=True, timeout=30)
            if r.stdout: out.append(r.stdout)
    return out, duration

def video_frames(data, n=6):
    try:
        try:
            import av  # noqa: F401
            frames, duration = _frames_pyav(data, n)
        except ImportError:
            if not shutil.which("ffmpeg"): raise HTTPError(503, "Video uploads aren't enabled on this server")
            frames, duration = _frames_ffmpeg(data, n)
    except HTTPError:
        raise
    except Exception:
        traceback.print_exc()
        raise HTTPError(400, "Couldn't read this video. Try a different file.")
    if not frames: raise HTTPError(400, "Couldn't read this video. Try a different file.")
    if duration > MAX_VIDEO_SECONDS + 5: raise HTTPError(400, f"Videos can be at most {MAX_VIDEO_SECONDS // 60} minutes")
    return frames, duration

def store_media(c, data, ctype):
    blob = psycopg2.Binary(data) if PG else data
    return str(c.execute("INSERT INTO images(ctype,data) VALUES(?,?) RETURNING id", (ctype, blob)).fetchone()["id"])

# ---- Video storage: S3-compatible bucket (Cloudflare R2, Backblaze B2, AWS S3) instead of the database ----
# Env, all required to switch it on: S3_ENDPOINT, S3_BUCKET, S3_ACCESS_KEY_ID, S3_SECRET_ACCESS_KEY, S3_PUBLIC_URL (optional S3_REGION, default "auto").
# Without them videos keep being stored in the database as before.
def s3_conf():
    v = {k: os.environ.get(k, "").strip() for k in ("S3_ENDPOINT", "S3_BUCKET", "S3_ACCESS_KEY_ID", "S3_SECRET_ACCESS_KEY", "S3_PUBLIC_URL")}
    if not all(v.values()): return None
    v["S3_ENDPOINT"], v["S3_PUBLIC_URL"] = v["S3_ENDPOINT"].rstrip("/"), v["S3_PUBLIC_URL"].rstrip("/")
    v["S3_REGION"] = os.environ.get("S3_REGION", "").strip() or "auto"
    return v

def sigv4_auth(method, path, hdrs, payload_hash, access, secret, region, amz_date):
    """AWS Signature Version 4 Authorization header (no query string). hdrs: lowercase header names -> values to sign."""
    day, signed = amz_date[:8], ";".join(sorted(hdrs))
    canon = "\n".join([method, path, "", "".join(f"{k}:{hdrs[k]}\n" for k in sorted(hdrs)), signed, payload_hash])
    scope = f"{day}/{region}/s3/aws4_request"
    to_sign = "\n".join(["AWS4-HMAC-SHA256", amz_date, scope, hashlib.sha256(canon.encode()).hexdigest()])
    k = ("AWS4" + secret).encode()
    for part in (day, region, "s3", "aws4_request"): k = hmac.new(k, part.encode(), hashlib.sha256).digest()
    sig = hmac.new(k, to_sign.encode(), hashlib.sha256).hexdigest()
    return f"AWS4-HMAC-SHA256 Credential={access}/{scope}, SignedHeaders={signed}, Signature={sig}"

def s3_request(method, key, body=b"", ctype=None):
    cf = s3_conf()
    if not cf: raise HTTPError(503, "Video storage is not set up on this server")
    host = urllib.parse.urlparse(cf["S3_ENDPOINT"]).netloc
    path = "/" + cf["S3_BUCKET"] + "/" + urllib.parse.quote(key, safe="/-_.~")
    amz = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    phash = hashlib.sha256(body).hexdigest()
    signed = {"host": host, "x-amz-content-sha256": phash, "x-amz-date": amz}
    if ctype: signed["content-type"] = ctype
    headers = {"x-amz-content-sha256": phash, "x-amz-date": amz,
               "Authorization": sigv4_auth(method, path, signed, phash, cf["S3_ACCESS_KEY_ID"], cf["S3_SECRET_ACCESS_KEY"], cf["S3_REGION"], amz)}
    if ctype: headers["Content-Type"] = ctype
    if method == "PUT": headers["Cache-Control"] = "public, max-age=31536000, immutable"
    req = urllib.request.Request(cf["S3_ENDPOINT"] + path, data=body if method == "PUT" else None, method=method, headers=headers)
    try:
        urllib.request.urlopen(req, timeout=300).read()
    except urllib.error.HTTPError as e:
        print("storage error:", method, key, e.code, e.read()[:300], flush=True)
        raise HTTPError(502, "Couldn't save the video to storage")
    except Exception:
        traceback.print_exc()
        raise HTTPError(502, "Couldn't reach video storage")

def s3_delete_quiet(name):
    try: s3_request("DELETE", str(name)[3:])
    except Exception as e: print("storage: couldn't delete", name, getattr(e, "msg", e), flush=True)

def store_video(c, data):
    """Returns the name to keep in posts.image: 's3:<key>' for the bucket, or a database media id."""
    if s3_conf():
        key = f"videos/{secrets.token_hex(16)}.mp4"
        s3_request("PUT", key, data, "video/mp4")
        return "s3:" + key
    return store_media(c, data, "video/mp4")

def save_image(c, b):
    ctype = sniff(b)
    if not ctype or len(b) > MAX_IMAGE_BYTES: raise HTTPError(400, "Unsupported, empty or too large image")
    moderate_images([(b, ctype)])
    return store_media(c, b, ctype)

def remove_image(c, name):
    if name and str(name).startswith("s3:"): s3_delete_quiet(name)
    elif name and str(name).isdigit(): c.execute("DELETE FROM images WHERE id=?", (int(name),))

EMAIL_RE = re.compile(r"[^@\s]+@[^@\s]+\.[^@\s]+")

def send_mail(to, subject, text):
    """Sends via Brevo's HTTPS API (Render's free tier blocks SMTP ports). Env: BREVO_API_KEY, MAIL_FROM."""
    key, sender = os.environ.get("BREVO_API_KEY"), os.environ.get("MAIL_FROM")
    if not key or not sender:
        if os.environ.get("MAIL_DEBUG"):
            print(f"[mail] to={to} subject={subject}\n{text}", flush=True); return
        raise HTTPError(503, "Email is not set up on this server yet")
    req = urllib.request.Request(
        "https://api.brevo.com/v3/smtp/email",
        data=json.dumps({"sender": {"name": "Viora", "email": sender}, "to": [{"email": to}],
                         "subject": subject, "textContent": text}).encode(),
        headers={"api-key": key, "content-type": "application/json", "accept": "application/json"})
    try:
        urllib.request.urlopen(req, timeout=15).read()
    except Exception:
        traceback.print_exc()
        raise HTTPError(502, "Couldn't send the email. Try again later.")

def hash_code(code, uid, kind):
    return hashlib.sha256(f"{kind}:{uid}:{code}".encode()).hexdigest()

def issue_code(c, uid, kind, email):
    now = time.time()
    last = c.execute("SELECT created FROM codes WHERE user_id=? AND kind=? ORDER BY id DESC LIMIT 1", (uid, kind)).fetchone()
    if last and now - last["created"] < 60: raise HTTPError(429, "Please wait a minute before requesting another code")
    code = "%06d" % secrets.randbelow(10 ** 6)
    c.execute("DELETE FROM codes WHERE user_id=? AND kind=?", (uid, kind))
    c.execute("INSERT INTO codes(user_id,kind,email,code_hash,expires,created) VALUES(?,?,?,?,?,?)",
              (uid, kind, email, hash_code(code, uid, kind), now + 900, now))
    return code

def check_code(c, uid, kind, code):
    """Returns the email the code was sent to. 5 wrong tries burns the code; codes last 15 minutes."""
    r = c.execute("SELECT * FROM codes WHERE user_id=? AND kind=? ORDER BY id DESC LIMIT 1", (uid, kind)).fetchone()
    if not r or r["expires"] < time.time(): raise HTTPError(400, "Code expired. Request a new one.")
    if r["attempts"] >= 5: raise HTTPError(400, "Too many wrong tries. Request a new code.")
    if not hmac.compare_digest(r["code_hash"], hash_code(str(code).strip(), uid, kind)):
        c.execute("UPDATE codes SET attempts=attempts+1 WHERE id=?", (r["id"],))
        c.c.commit()  # keep the counter even though we raise
        raise HTTPError(400, "Wrong code")
    return r["email"]

def vip_active(u):
    """VIP = granted by admin (lifetime) or an active paid subscription."""
    return bool(u["is_vip"]) or (u["vip_until"] or 0) > time.time()

# ---- Stripe (plain HTTPS, no SDK) ----
def stripe_api(method, path, params=None):
    key = os.environ.get("STRIPE_SECRET_KEY")
    if not key: raise HTTPError(503, "Payments are not set up on this server yet")
    req = urllib.request.Request("https://api.stripe.com" + path, method=method,
                                 data=urllib.parse.urlencode(params).encode() if params else None,
                                 headers={"Authorization": "Bearer " + key})
    try:
        return json.loads(urllib.request.urlopen(req, timeout=20).read())
    except urllib.error.HTTPError as e:
        body = e.read()
        print("stripe error:", e.code, body[:500], flush=True)
        try: detail = json.loads(body)["error"]["message"][:200]
        except Exception: detail = ""
        raise HTTPError(502, "The payment provider rejected the request" + (f": {detail}" if detail else ""))
    except Exception:
        traceback.print_exc()
        raise HTTPError(502, "Couldn't reach the payment provider")

def plan_list():
    if not os.environ.get("STRIPE_SECRET_KEY"): return []
    out = []
    for pid, name in (("monthly", "Monthly"), ("yearly", "Yearly")):
        price = os.environ.get(f"STRIPE_PRICE_{pid.upper()}")
        if price: out.append({"id": pid, "name": name, "price_id": price, "price": os.environ.get(f"PLAN_{pid.upper()}_TEXT", "")})
    return out

# ---- Boosted posts ("Sponsored" in other people's feeds) ----
def boost_currency():
    return os.environ.get("BOOST_CURRENCY", "usd").strip().lower() or "usd"

def boost_plans():
    """Env BOOST_PLANS="days:cents,..." (default 1 day $1.99, 3 days $4.99, 7 days $8.99); BOOST_CURRENCY (default usd)."""
    cur, out = boost_currency(), []
    for part in os.environ.get("BOOST_PLANS", "1:199,3:499,7:899").split(","):
        try:
            d, cents = (int(x) for x in part.strip().split(":"))
        except ValueError:
            continue
        if d < 1 or cents < 50: continue
        out.append({"days": d, "cents": cents, "price": f"${cents / 100:.2f}" if cur == "usd" else f"{cents / 100:.2f} {cur.upper()}"})
    return out

def activate_boost(c, post_id, uid, days, session_id=None):
    if session_id and c.execute("SELECT 1 FROM boosts WHERE session_id=?", (session_id,)).fetchone(): return  # webhook retry
    if not c.execute("SELECT 1 FROM posts WHERE id=? AND user_id=?", (post_id, uid)).fetchone():
        print("boost: post no longer exists", post_id, session_id, flush=True); return
    now = time.time()
    last = c.execute("SELECT MAX(ends) AS e FROM boosts WHERE post_id=?", (post_id,)).fetchone()["e"] or 0
    c.execute("INSERT INTO boosts(post_id,user_id,days,ends,created,session_id) VALUES(?,?,?,?,?,?)",
              (post_id, uid, days, max(now, float(last)) + days * 86400, now, session_id))

def boost_from_session(c, obj):
    if obj.get("payment_status") != "paid": return
    m = obj.get("metadata") or {}
    try:
        activate_boost(c, int(m["boost_post_id"]), int(m["user_id"]), int(m["boost_days"]), obj.get("id"))
    except (KeyError, ValueError):
        print("stripe: bad boost metadata", obj.get("id"), flush=True)

def verify_stripe_signature(h, raw):
    secret = os.environ.get("STRIPE_WEBHOOK_SECRET", "")
    ts, sigs = None, []
    for kv in h.headers.get("Stripe-Signature", "").split(","):
        k, _, v = kv.partition("=")
        if k.strip() == "t": ts = v.strip()
        elif k.strip() == "v1": sigs.append(v.strip())
    if not secret or not ts or not sigs: return False
    try:
        if abs(time.time() - int(ts)) > 300: return False
    except ValueError:
        return False
    expected = hmac.new(secret.encode(), ts.encode() + b"." + raw, hashlib.sha256).hexdigest()
    return any(hmac.compare_digest(expected, x) for x in sigs)

def sub_period_end(sub):
    """Stripe's newer API versions keep the period end on each subscription item; older ones on the subscription."""
    ends = [it.get("current_period_end") for it in ((sub.get("items") or {}).get("data") or []) if it.get("current_period_end")]
    if sub.get("current_period_end"): ends.append(sub["current_period_end"])
    return max(ends) if ends else None

def apply_subscription(c, sub):
    uid, cust = (sub.get("metadata") or {}).get("user_id"), sub.get("customer")
    row = None
    if uid and str(uid).isdigit(): row = c.execute("SELECT id FROM users WHERE id=?", (int(uid),)).fetchone()
    if not row and cust: row = c.execute("SELECT id FROM users WHERE stripe_customer=?", (cust,)).fetchone()
    if not row:
        print("stripe: subscription for unknown user", sub.get("id"), flush=True); return
    paid = sub.get("status") in ("active", "trialing", "past_due")
    end = float(sub_period_end(sub)) if paid and sub_period_end(sub) else None
    c.execute("UPDATE users SET vip_until=?, stripe_customer=COALESCE(?, stripe_customer) WHERE id=?", (end, cust, row["id"]))

def account_id(uid):
    """Opaque id attached to Google Play purchases so a purchase can be tied to one Viora account."""
    return hashlib.sha256(f"viora:{uid}".encode()).hexdigest()[:32]

# ---- Google Play Billing (server-side verification, service account auth) ----
GOOGLE_API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
_gtoken = {"v": None, "exp": 0.0}

def _b64u(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()

def google_access_token():
    if _gtoken["v"] and time.time() < _gtoken["exp"] - 60: return _gtoken["v"]
    raw = os.environ.get("GOOGLE_SERVICE_ACCOUNT_JSON")
    if not raw: raise HTTPError(503, "Google Play billing is not set up on this server yet")
    try:
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import padding
        sa = json.loads(raw); now = int(time.time())
        head = _b64u(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
        claims = _b64u(json.dumps({"iss": sa["client_email"], "scope": "https://www.googleapis.com/auth/androidpublisher",
                                   "aud": "https://oauth2.googleapis.com/token", "iat": now, "exp": now + 3600}).encode())
        key = serialization.load_pem_private_key(sa["private_key"].encode(), password=None)
        sig = key.sign(f"{head}.{claims}".encode(), padding.PKCS1v15(), hashes.SHA256())
        data = urllib.parse.urlencode({"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
                                       "assertion": f"{head}.{claims}.{_b64u(sig)}"}).encode()
        r = json.loads(urllib.request.urlopen(urllib.request.Request("https://oauth2.googleapis.com/token", data=data), timeout=20).read())
        _gtoken["v"], _gtoken["exp"] = r["access_token"], time.time() + int(r.get("expires_in", 3600))
        return _gtoken["v"]
    except Exception:
        traceback.print_exc()
        raise HTTPError(502, "Couldn't sign in to Google Play. Check GOOGLE_SERVICE_ACCOUNT_JSON.")

def google_request(method, url, body=None):
    token = google_access_token()
    req = urllib.request.Request(url, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    try:
        raw = urllib.request.urlopen(req, timeout=20).read()
        return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        detail = e.read()[:400]
        print("google play error:", e.code, detail, flush=True)
        raise HTTPError(400 if e.code in (400, 404, 410) else 502, "Google Play couldn't confirm this purchase")
    except Exception:
        traceback.print_exc()
        raise HTTPError(502, "Couldn't reach Google Play")

def parse_rfc3339(s):
    return datetime.datetime.fromisoformat(re.sub(r"\.\d+", "", s).replace("Z", "+00:00")).timestamp()

def google_apply(c, uid, token, acknowledge):
    pkg = os.environ.get("GOOGLE_PLAY_PACKAGE", "io.github.jimchen67868.viora")
    product = os.environ.get("GOOGLE_PLAY_PRODUCT_ID", "viora_vip")
    quoted = urllib.parse.quote(token, safe="")
    sub = google_request("GET", f"{GOOGLE_API}/{pkg}/purchases/subscriptionsv2/tokens/{quoted}")
    items = [li for li in sub.get("lineItems", []) if li.get("productId") == product]
    if not items: raise HTTPError(400, "This purchase isn't for Viora VIP")
    acct = (sub.get("externalAccountIdentifiers") or {}).get("obfuscatedExternalAccountId")
    if acct and acct != account_id(uid): raise HTTPError(403, "This purchase belongs to a different account")
    if c.execute("SELECT 1 FROM users WHERE play_token=? AND id<>?", (token, uid)).fetchone():
        raise HTTPError(409, "This purchase is already linked to another account")
    ends = [parse_rfc3339(li["expiryTime"]) for li in items if li.get("expiryTime")]
    end = max(ends) if ends else 0
    entitled = sub.get("subscriptionState") in ("SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_CANCELED",
                                                "SUBSCRIPTION_STATE_IN_GRACE_PERIOD") and end > time.time()
    if entitled and acknowledge and sub.get("acknowledgementState") == "ACKNOWLEDGEMENT_STATE_PENDING":
        google_request("POST", f"{GOOGLE_API}/{pkg}/purchases/subscriptions/{product}/tokens/{quoted}:acknowledge", {})
    cur = c.execute("SELECT play_token FROM users WHERE id=?", (uid,)).fetchone()
    if entitled:
        c.execute("UPDATE users SET vip_until=?, play_token=? WHERE id=?", (end, token, uid))
    elif cur["play_token"] == token:  # this Play subscription ended or was refunded
        c.execute("UPDATE users SET vip_until=NULL WHERE id=?", (uid,))

ROUTES = []
def route(method, pattern):
    def deco(fn):
        ROUTES.append((method, re.compile("^" + pattern + "$"), fn)); return fn
    return deco

class H(BaseHTTPRequestHandler):
    server_version = "Viora/1.0"

    # ---- helpers ----
    def send_json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > MAX_BODY: raise HTTPError(413, "Upload too large")
        return self.rfile.read(n) if n else b""

    def json_body(self):
        try:
            d = json.loads(self.body() or b"{}")
            return d if isinstance(d, dict) else {}
        except ValueError:
            raise HTTPError(400, "Invalid JSON")

    def user(self, c):
        auth = self.headers.get("Authorization", "")
        if not auth.startswith("Bearer "): raise HTTPError(401, "Not signed in")
        r = c.execute("SELECT u.* FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token=?", (auth[7:],)).fetchone()
        if not r: raise HTTPError(401, "Session expired, please sign in again")
        return r

    def origin(self):
        return f"{self.headers.get('X-Forwarded-Proto', 'http')}://{self.headers.get('Host', 'localhost')}"

    def send_html(self, code, html):
        body = html.encode()
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def url(self, f):
        if not f: return None
        if str(f).startswith("s3:"):
            cf = s3_conf()
            return f"{cf['S3_PUBLIC_URL']}/{str(f)[3:]}" if cf else None
        proto = self.headers.get("X-Forwarded-Proto", "http")
        return f"{proto}://{self.headers.get('Host', 'localhost')}/uploads/{f}"

    def profile(self, u):
        with db() as c:
            fr = c.execute("SELECT COUNT(*) AS n FROM follows WHERE followee_id=?", (u["id"],)).fetchone()["n"]
            fg = c.execute("SELECT COUNT(*) AS n FROM follows WHERE follower_id=?", (u["id"],)).fetchone()["n"]
            rq = c.execute("SELECT COUNT(*) AS n FROM follow_requests WHERE target_id=?", (u["id"],)).fetchone()["n"]
        return {"is_private": bool(u["is_private"]), "follow_requests": rq, "username": u["username"], "bio": u["bio"], "avatar": self.url(u["avatar"]),
                "email": u["email"], "email_verified": bool(u["email_verified"]), "vip": vip_active(u),
                "followers": fr, "following": fg, "account_id": account_id(u["id"]), "following_privacy": u["following_privacy"] or "everyone",
                "vip_until": (u["vip_until"] if not u["is_vip"] and (u["vip_until"] or 0) > time.time() else None)}

    def post_json(self, c, uid, where="", args=(), limit=100):
        vis = "(u.is_private=0 OR p.user_id=? OR p.user_id IN (SELECT followee_id FROM follows WHERE follower_id=?))"
        where = f"{where} AND {vis}" if where.strip() else f"WHERE {vis}"
        args = (*args, uid, uid)
        rows = c.execute(f"""SELECT p.id, p.caption, p.image, p.kind, p.thumb, p.created, u.username, u.avatar,
            (SELECT COUNT(*) FROM likes WHERE post_id=p.id) AS likes,
            EXISTS(SELECT 1 FROM likes WHERE post_id=p.id AND user_id=?) AS liked,
            (SELECT COUNT(*) FROM comments WHERE post_id=p.id) AS comments,
            (SELECT COUNT(*) FROM post_views WHERE post_id=p.id) AS views
            FROM posts p JOIN users u ON u.id=p.user_id {where} ORDER BY p.id DESC LIMIT {int(limit)}""", (uid, *args)).fetchall()
        return [{"id": r["id"], "user": r["username"], "avatar": self.url(r["avatar"]), "image": self.url(r["image"]),
                 "caption": r["caption"], "kind": r["kind"], "thumb": self.url(r["thumb"]), "likes": r["likes"], "liked": bool(r["liked"]), "comments": r["comments"], "created": int(r["created"] or 0), "views": r["views"]} for r in rows]

    # ---- dispatch ----
    def dispatch(self, method):
        u = urlparse(self.path)
        try:
            if method == "GET" and u.path.startswith("/uploads/"):
                return self.serve(u.path[len("/uploads/"):])
            for m, rx, fn in ROUTES:
                if m == method and (mt := rx.match(u.path)):
                    return fn(self, parse_qs(u.query), *mt.groups())
            raise HTTPError(404, "Not found")
        except HTTPError as e:
            self.send_json(e.code, {"error": e.msg})
        except Exception:
            traceback.print_exc()
            self.send_json(500, {"error": "Server error"})

    def serve(self, name):
        if not name.isdigit(): raise HTTPError(404, "Not found")
        mid = int(name)
        with db() as c:
            r = c.execute("SELECT ctype, LENGTH(data) AS total FROM images WHERE id=?", (mid,)).fetchone()
            if not r: raise HTTPError(404, "Not found")
            total = int(r["total"] or 0); start, end, code = 0, total - 1, 200
            rng = self.headers.get("Range", "")
            if rng.startswith("bytes=") and total:  # videos need byte ranges to play and seek
                a, _, b = rng[6:].split(",")[0].partition("-")
                try:
                    if a == "": start = max(0, total - int(b))
                    else: start, end = int(a), (int(b) if b else total - 1)
                    end = min(end, total - 1); code = 206
                except ValueError:
                    start, end, code = 0, total - 1, 200
                if code == 206 and start > end:
                    self.send_response(416); self.send_header("Content-Range", f"bytes */{total}")
                    self.send_header("Content-Length", "0"); self.end_headers(); return
            self.send_response(code)
            self.send_header("Content-Type", r["ctype"])
            self.send_header("Content-Length", str(max(0, end - start + 1)))
            self.send_header("Accept-Ranges", "bytes")
            if code == 206: self.send_header("Content-Range", f"bytes {start}-{end}/{total}")
            self.send_header("Cache-Control", "public, max-age=31536000, immutable")
            self.end_headers()
            pos = start  # read 4 MB at a time so a long video never sits fully in memory
            while pos <= end:
                n = min(4 * 1024 * 1024, end - pos + 1)
                row = c.execute("SELECT SUBSTR(data, ?, ?) AS part FROM images WHERE id=?", (pos + 1, n, mid)).fetchone()
                part = bytes(row["part"] or b"") if row else b""
                if not part: break
                self.wfile.write(part); pos += len(part)

    def do_GET(self): self.dispatch("GET")
    def do_POST(self): self.dispatch("POST")
    def do_PUT(self): self.dispatch("PUT")
    def do_DELETE(self): self.dispatch("DELETE")

# ---------------- routes ----------------
@route("GET", "/api/health")
def health(h, q): h.send_json(200, {"ok": True})

def new_session(c, uid):
    t = secrets.token_urlsafe(32)
    c.execute("INSERT INTO sessions(token,user_id,created) VALUES(?,?,?)", (t, uid, time.time()))
    return t

@route("POST", "/api/register")
def register(h, q):
    d = h.json_body(); name = str(d.get("username", "")).strip(); pw = str(d.get("password", ""))
    if not re.fullmatch(r"[A-Za-z0-9_.]{3,20}", name): raise HTTPError(400, "Username: 3-20 letters, digits, _ or .")
    if len(pw) < 6: raise HTTPError(400, "Password must be at least 6 characters")
    salt = secrets.token_hex(16)
    with db() as c:
        if c.execute("SELECT 1 FROM users WHERE LOWER(username)=LOWER(?)", (name,)).fetchone():
            raise HTTPError(409, "Username already taken")
        try:
            uid = c.execute("INSERT INTO users(username,salt,pw) VALUES(?,?,?) RETURNING id", (name, salt, hash_pw(pw, salt))).fetchone()["id"]
        except INTEGRITY:
            raise HTTPError(409, "Username already taken")
        tok = new_session(c, uid)
        u = c.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone()
    h.send_json(201, {"token": tok, "user": h.profile(u)})

@route("POST", "/api/login")
def login(h, q):
    d = h.json_body()
    with db() as c:
        u = c.execute("SELECT * FROM users WHERE LOWER(username)=LOWER(?)", (str(d.get("username", "")).strip(),)).fetchone()
        if not u or not hmac.compare_digest(u["pw"], hash_pw(str(d.get("password", "")), u["salt"])):
            raise HTTPError(401, "Wrong username or password")
        tok = new_session(c, u["id"])
    h.send_json(200, {"token": tok, "user": h.profile(u)})

@route("GET", "/api/me")
def me(h, q):
    with db() as c: h.send_json(200, {"user": h.profile(h.user(c))})

@route("PUT", "/api/me")
def update_me(h, q):
    d = h.json_body()
    with db() as c:
        u = h.user(c)
        bio = str(d.get("bio", u["bio"]))[:150]
        name = str(d.get("username", u["username"])).strip()
        if name != u["username"]:
            if not re.fullmatch(r"[A-Za-z0-9_.]{3,20}", name): raise HTTPError(400, "Username: 3-20 letters, digits, _ or .")
            if c.execute("SELECT 1 FROM users WHERE LOWER(username)=LOWER(?) AND id<>?", (name, u["id"])).fetchone():
                raise HTTPError(409, "Username already taken")
        try:
            c.execute("UPDATE users SET bio=?, username=? WHERE id=?", (bio, name, u["id"]))
        except INTEGRITY:
            raise HTTPError(409, "Username already taken")
        u = c.execute("SELECT * FROM users WHERE id=?", (u["id"],)).fetchone()
    h.send_json(200, {"user": h.profile(u)})

@route("PUT", "/api/me/account")
def set_account(h, q):
    private = 1 if h.json_body().get("private") is True else 0
    with db() as c:
        u = h.user(c)
        c.execute("UPDATE users SET is_private=? WHERE id=?", (private, u["id"]))
        if private:   # private accounts can't be promoted: every running or queued boost stops now and is not restored
            c.execute("UPDATE boosts SET ends=?, paused_at=NULL WHERE user_id=? AND ends>?", (time.time(), u["id"], time.time()))
        if not private:   # going public approves everyone who was waiting
            c.execute("INSERT INTO follows(follower_id,followee_id,created) SELECT requester_id, target_id, ? FROM follow_requests WHERE target_id=? ON CONFLICT DO NOTHING",
                      (time.time(), u["id"]))
            c.execute("DELETE FROM follow_requests WHERE target_id=?", (u["id"],))
        u = c.execute("SELECT * FROM users WHERE id=?", (u["id"],)).fetchone()
    h.send_json(200, {"user": h.profile(u)})

@route("GET", "/api/follow-requests")
def list_requests(h, q):
    with db() as c:
        me = h.user(c)
        rows = c.execute("SELECT u.username, u.bio, u.avatar FROM follow_requests f JOIN users u ON u.id=f.requester_id WHERE f.target_id=? "
                         "ORDER BY f.created DESC LIMIT 200", (me["id"],)).fetchall()
    h.send_json(200, {"users": [{"username": r["username"], "bio": r["bio"], "avatar": h.url(r["avatar"])} for r in rows]})

@route("POST", r"/api/follow-requests/([A-Za-z0-9_.]{3,20})/(accept|decline)")
def answer_request(h, q, name, action):
    with db() as c:
        me = h.user(c); r = target_user(c, name)
        if not c.execute("SELECT 1 FROM follow_requests WHERE requester_id=? AND target_id=?", (r["id"], me["id"])).fetchone():
            raise HTTPError(404, "That request is no longer there")
        c.execute("DELETE FROM follow_requests WHERE requester_id=? AND target_id=?", (r["id"], me["id"]))
        if action == "accept":
            c.execute("INSERT INTO follows(follower_id,followee_id,created) VALUES(?,?,?) ON CONFLICT DO NOTHING", (r["id"], me["id"], time.time()))
            notify(c, r["id"], me, "follow_accept", "accepted your follow request")
    h.send_json(200, {"ok": True})

@route("PUT", "/api/me/privacy")
def set_privacy(h, q):
    mode = str(h.json_body().get("following_privacy", "")).strip()
    if mode not in ("everyone", "followers", "me"): raise HTTPError(400, "Invalid privacy option")
    with db() as c:
        u = h.user(c)
        c.execute("UPDATE users SET following_privacy=? WHERE id=?", (mode, u["id"]))
        u = c.execute("SELECT * FROM users WHERE id=?", (u["id"],)).fetchone()
    h.send_json(200, {"user": h.profile(u)})

@route("POST", "/api/me/avatar")
def set_avatar(h, q):
    with db() as c:
        u = h.user(c); name = save_image(c, h.body())
        c.execute("UPDATE users SET avatar=? WHERE id=?", (name, u["id"]))
        remove_image(c, u["avatar"])
        u = c.execute("SELECT * FROM users WHERE id=?", (u["id"],)).fetchone()
    h.send_json(200, {"user": h.profile(u)})

@route("DELETE", "/api/me/avatar")
def del_avatar(h, q):
    with db() as c:
        u = h.user(c)
        c.execute("UPDATE users SET avatar=NULL WHERE id=?", (u["id"],)); remove_image(c, u["avatar"])
        u = c.execute("SELECT * FROM users WHERE id=?", (u["id"],)).fetchone()
    h.send_json(200, {"user": h.profile(u)})


def clean_email(d):
    e = str(d.get("email", "")).strip()
    if len(e) > 254 or not EMAIL_RE.fullmatch(e): raise HTTPError(400, "Enter a valid email address")
    return e

@route("PUT", "/api/me/email")
def set_email(h, q):
    email = clean_email(h.json_body())
    with db() as c:
        u = h.user(c)
        if u["email"] and u["email"].lower() == email.lower() and u["email_verified"]:
            return h.send_json(200, {"user": h.profile(u)})
        if c.execute("SELECT 1 FROM users WHERE LOWER(email)=LOWER(?) AND email_verified=1 AND id<>?", (email, u["id"])).fetchone():
            raise HTTPError(409, "That email is already used by another account")
        code = issue_code(c, u["id"], "verify", email)
        c.execute("UPDATE users SET email=?, email_verified=0 WHERE id=?", (email, u["id"]))
        send_mail(email, "Your Viora verification code", f"Your Viora verification code is {code}\n\nIt expires in 15 minutes. If you didn't ask for this, ignore this email.")
        u = c.execute("SELECT * FROM users WHERE id=?", (u["id"],)).fetchone()
    h.send_json(200, {"user": h.profile(u)})

@route("POST", "/api/me/email/send")
def resend_email_code(h, q):
    with db() as c:
        u = h.user(c)
        if not u["email"] or u["email_verified"]: raise HTTPError(400, "No email waiting for verification")
        code = issue_code(c, u["id"], "verify", u["email"])
        send_mail(u["email"], "Your Viora verification code", f"Your Viora verification code is {code}\n\nIt expires in 15 minutes.")
    h.send_json(200, {"ok": True})

@route("POST", "/api/me/email/verify")
def verify_email(h, q):
    code = h.json_body().get("code", "")
    with db() as c:
        u = h.user(c)
        if not u["email"]: raise HTTPError(400, "Add an email first")
        sent_to = check_code(c, u["id"], "verify", code)
        if sent_to.lower() != u["email"].lower(): raise HTTPError(400, "Code was for a different email. Request a new one.")
        try:
            c.execute("UPDATE users SET email_verified=1 WHERE id=?", (u["id"],))
        except INTEGRITY:
            raise HTTPError(409, "That email is already used by another account")
        c.execute("DELETE FROM codes WHERE user_id=? AND kind=?", (u["id"], "verify"))
        u = c.execute("SELECT * FROM users WHERE id=?", (u["id"],)).fetchone()
    h.send_json(200, {"user": h.profile(u)})

@route("DELETE", "/api/me/email")
def remove_email(h, q):
    with db() as c:
        u = h.user(c)
        c.execute("UPDATE users SET email=NULL, email_verified=0 WHERE id=?", (u["id"],))
        c.execute("DELETE FROM codes WHERE user_id=?", (u["id"],))
        u = c.execute("SELECT * FROM users WHERE id=?", (u["id"],)).fetchone()
    h.send_json(200, {"user": h.profile(u)})

@route("POST", "/api/password/forgot")
def forgot_password(h, q):
    email = clean_email(h.json_body())
    try:
        with db() as c:
            u = c.execute("SELECT * FROM users WHERE LOWER(email)=LOWER(?) AND email_verified=1", (email,)).fetchone()
            if u:
                code = issue_code(c, u["id"], "reset", u["email"])
                send_mail(u["email"], "Reset your Viora password",
                          f"Hi {u['username']},\n\nYour Viora password reset code is {code}\n\nIt expires in 15 minutes. If you didn't ask for this, ignore this email.")
    except HTTPError as e:
        print("forgot_password:", e.code, e.msg, flush=True)  # same reply either way: don't reveal which emails exist
    h.send_json(200, {"ok": True})

@route("POST", "/api/password/reset")
def reset_password(h, q):
    d = h.json_body(); email = clean_email(d); pw = str(d.get("password", ""))
    if len(pw) < 6: raise HTTPError(400, "Password must be at least 6 characters")
    with db() as c:
        u = c.execute("SELECT * FROM users WHERE LOWER(email)=LOWER(?) AND email_verified=1", (email,)).fetchone()
        if not u: raise HTTPError(400, "Wrong code")
        check_code(c, u["id"], "reset", d.get("code", ""))
        salt = secrets.token_hex(16)
        c.execute("UPDATE users SET salt=?, pw=? WHERE id=?", (salt, hash_pw(pw, salt), u["id"]))
        c.execute("DELETE FROM sessions WHERE user_id=?", (u["id"],))
        c.execute("DELETE FROM codes WHERE user_id=?", (u["id"],))
    h.send_json(200, {"ok": True})

@route("GET", r"/api/users/([A-Za-z0-9_.]{3,20})")
def user_profile(h, q, name):
    with db() as c:
        me = h.user(c)
        u = c.execute("SELECT * FROM users WHERE LOWER(username)=LOWER(?)", (name,)).fetchone()
        if not u: raise HTTPError(404, "User not found")
        posts = h.post_json(c, me["id"], "WHERE p.user_id=?", (u["id"],))
        fr = c.execute("SELECT COUNT(*) AS n FROM follows WHERE followee_id=?", (u["id"],)).fetchone()["n"]
        fg = c.execute("SELECT COUNT(*) AS n FROM follows WHERE follower_id=?", (u["id"],)).fetchone()["n"]
        isf = c.execute("SELECT 1 FROM follows WHERE follower_id=? AND followee_id=?", (me["id"], u["id"])).fetchone() is not None
        requested = c.execute("SELECT 1 FROM follow_requests WHERE requester_id=? AND target_id=?", (me["id"], u["id"])).fetchone() is not None
        private = bool(u["is_private"])
    # public view: never include the email
    h.send_json(200, {"user": {"username": u["username"], "bio": u["bio"], "avatar": h.url(u["avatar"]),
                               "followers": fr, "following": fg, "is_following": isf, "is_private": private, "requested": requested,
                               "locked": private and not isf and u["id"] != me["id"]}, "posts": posts})

@route("GET", "/api/search")
def search(h, q):
    term = q.get("q", [""])[0].strip()[:50]
    with db() as c:
        me = h.user(c)
        if not term: return h.send_json(200, {"users": [], "posts": []})
        pat = "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
        rows = c.execute("SELECT username, bio, avatar FROM users WHERE LOWER(username) LIKE LOWER(?) ESCAPE '!' ORDER BY LOWER(username) LIMIT 20", (pat,)).fetchall()
        posts = h.post_json(c, me["id"], "WHERE LOWER(p.caption) LIKE LOWER(?) ESCAPE '!'", (pat,))
    h.send_json(200, {"users": [{"username": r["username"], "bio": r["bio"], "avatar": h.url(r["avatar"])} for r in rows], "posts": posts})

STOP = set("the and for with this that you your are was were have has had not but from they them their our out all can will just into over about more some what when who how why its it's i'm".split())

def words(text):
    return {w for w in re.findall(r"#?\w{3,}", (text or "").lower()) if w not in STOP}

@route("GET", "/api/feed/for-you")
def for_you(h, q):
    """VIP-only feed ranked by similarity to what the user has liked: same authors and shared words/#hashtags."""
    with db() as c:
        u = h.user(c)
        if not vip_active(u): raise HTTPError(403, "For You is a VIP feature")
        liked = c.execute("""SELECT au.username, p.caption FROM likes l JOIN posts p ON p.id=l.post_id
                             JOIN users au ON au.id=p.user_id WHERE l.user_id=? ORDER BY p.id DESC LIMIT 100""", (u["id"],)).fetchall()
        authors, kw = {}, {}
        for r in liked:
            authors[r["username"]] = authors.get(r["username"], 0) + 1
            for w in words(r["caption"]): kw[w] = kw.get(w, 0) + (2 if w.startswith("#") else 1)
        cand = h.post_json(c, u["id"], "WHERE p.user_id<>?", (u["id"],), limit=300)
    scored = []
    for i, p in enumerate(cand):  # cand is newest first
        if p["liked"]: continue
        score = (3 * min(authors.get(p["user"], 0), 5)
                 + min(sum(kw.get(w, 0) for w in words(p["caption"])), 10)
                 + math.log1p(p["likes"]) + max(0.0, 2 - i / 50))
        scored.append((-score, i, p))
    scored.sort(key=lambda t: (t[0], t[1]))
    h.send_json(200, {"posts": [p for _, _, p in scored[:50]], "personalized": bool(liked)})

@route("POST", "/api/admin/vip")
def set_vip(h, q):
    key = os.environ.get("ADMIN_KEY", "")
    if not key or not hmac.compare_digest(h.headers.get("X-Admin-Key", "").encode(), key.encode()):
        raise HTTPError(403, "Forbidden")
    d = h.json_body(); name = str(d.get("username", "")).strip(); vip = 1 if d.get("vip", True) else 0
    with db() as c:
        if not c.execute("UPDATE users SET is_vip=? WHERE LOWER(username)=LOWER(?)", (vip, name)).rowcount:
            raise HTTPError(404, "User not found")
    h.send_json(200, {"ok": True, "username": name, "vip": bool(vip)})

@route("GET", "/api/plans")
def plans(h, q):
    with db() as c: h.user(c)
    h.send_json(200, {"plans": [{k: p[k] for k in ("id", "name", "price")} for p in plan_list()]})

@route("POST", "/api/billing/checkout")
def checkout(h, q):
    plan = str(h.json_body().get("plan", ""))
    with db() as c:
        u = h.user(c)
        if u["is_vip"]: raise HTTPError(400, "Your account already has VIP")
        if (u["vip_until"] or 0) > time.time(): raise HTTPError(400, "You already have an active subscription. Use Manage.")
        email = u["email"] if u["email_verified"] else None
    chosen = next((p for p in plan_list() if p["id"] == plan), None)
    if not chosen: raise HTTPError(503 if not os.environ.get("STRIPE_SECRET_KEY") else 400, "That plan isn't available")
    params = {"mode": "subscription", "line_items[0][price]": chosen["price_id"], "line_items[0][quantity]": "1",
              "client_reference_id": str(u["id"]), "subscription_data[metadata][user_id]": str(u["id"]),
              "success_url": h.origin() + "/billing/return", "cancel_url": h.origin() + "/billing/return"}
    if u["stripe_customer"]: params["customer"] = u["stripe_customer"]
    elif email: params["customer_email"] = email
    try:
        session = stripe_api("POST", "/v1/checkout/sessions", params)
    except HTTPError:
        if "customer" not in params: raise
        # a customer id saved in test mode doesn't exist in live mode: retry as a new customer
        del params["customer"]
        if email: params["customer_email"] = email
        session = stripe_api("POST", "/v1/checkout/sessions", params)
    h.send_json(200, {"url": session["url"]})

@route("POST", "/api/billing/portal")
def portal(h, q):
    with db() as c: u = h.user(c)
    if not u["stripe_customer"]: raise HTTPError(400, "No subscription found for this account")
    s = stripe_api("POST", "/v1/billing_portal/sessions", {"customer": u["stripe_customer"], "return_url": h.origin() + "/billing/return"})
    h.send_json(200, {"url": s["url"]})

@route("GET", "/billing/return")
def billing_return(h, q):
    h.send_html(200, "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
                     "<body style='font-family:sans-serif;text-align:center;padding:3rem 1rem'>"
                     "<h2>All set</h2><p>You can close this page and return to the Viora app.</p>")

@route("POST", "/api/stripe/webhook")
def stripe_webhook(h, q):
    raw = h.body()
    if not verify_stripe_signature(h, raw): raise HTTPError(400, "Bad signature")
    try:
        event = json.loads(raw); kind = event["type"]; obj = event["data"]["object"]
    except (ValueError, KeyError):
        raise HTTPError(400, "Bad event")
    with db() as c:
        if kind in ("checkout.session.completed", "checkout.session.async_payment_succeeded") and (obj.get("metadata") or {}).get("boost_post_id"):
            boost_from_session(c, obj)
        elif kind == "checkout.session.completed":
            uid, cust, subid = obj.get("client_reference_id"), obj.get("customer"), obj.get("subscription")
            if uid and str(uid).isdigit() and cust:
                c.execute("UPDATE users SET stripe_customer=? WHERE id=?", (cust, int(uid)))
            if subid:
                try: apply_subscription(c, stripe_api("GET", f"/v1/subscriptions/{subid}"))
                except HTTPError as e: print("stripe: couldn't fetch subscription:", e.msg, flush=True)
        elif kind in ("customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted"):
            apply_subscription(c, obj)
    h.send_json(200, {"received": True})

@route("POST", "/api/billing/google/verify")
def google_verify(h, q):
    token = str(h.json_body().get("purchase_token", "")).strip()
    if not token or len(token) > 2000: raise HTTPError(400, "Missing purchase token")
    with db() as c:
        u = h.user(c)
        google_apply(c, u["id"], token, acknowledge=True)
        u = c.execute("SELECT * FROM users WHERE id=?", (u["id"],)).fetchone()
    h.send_json(200, {"user": h.profile(u)})

@route("POST", "/api/billing/google/sync")
def google_sync(h, q):
    """Re-checks the user's stored Play subscription with Google (catches cancellations, expiry and refunds)."""
    with db() as c:
        u = h.user(c)
        if u["play_token"]:
            google_apply(c, u["id"], u["play_token"], acknowledge=False)
            u = c.execute("SELECT * FROM users WHERE id=?", (u["id"],)).fetchone()
    h.send_json(200, {"user": h.profile(u)})

def post_visible(c, viewer_id, pid):
    """False if the post doesn't exist or belongs to a private account the viewer doesn't follow."""
    return c.execute("SELECT 1 FROM posts p JOIN users u ON u.id=p.user_id WHERE p.id=? AND (u.is_private=0 OR p.user_id=? OR "
                     "p.user_id IN (SELECT followee_id FROM follows WHERE follower_id=?))", (int(pid), viewer_id, viewer_id)).fetchone() is not None

def target_user(c, name):
    t = c.execute("SELECT * FROM users WHERE LOWER(username)=LOWER(?)", (name,)).fetchone()
    if not t: raise HTTPError(404, "User not found")
    return t

def follower_count(c, uid):
    return c.execute("SELECT COUNT(*) AS n FROM follows WHERE followee_id=?", (uid,)).fetchone()["n"]

# ---------- Push notifications (Firebase Cloud Messaging, HTTP v1) ----------
# Needs env FIREBASE_SERVICE_ACCOUNT = the whole service-account JSON from Firebase. Without it, pushes are skipped (the in-app Activity list still works).
def _der(buf, i):
    """Reads one DER element at i; returns (tag, content_start, content_end)."""
    tag, ln = buf[i], buf[i + 1]; i += 2
    if ln & 0x80:
        nb = ln & 0x7F; ln = int.from_bytes(buf[i:i + nb], "big"); i += nb
    return tag, i, i + ln

def rsa_key_from_pem(pem):
    """Returns (n, d) from a PKCS#8 ('BEGIN PRIVATE KEY') or PKCS#1 ('BEGIN RSA PRIVATE KEY') PEM."""
    der = base64.b64decode("".join(l for l in pem.strip().splitlines() if not l.startswith("-----")))
    _, s, _e = _der(der, 0)
    if "BEGIN PRIVATE KEY" in pem:
        _, _, i = _der(der, s)       # version
        _, _, i = _der(der, i)       # algorithm identifier
        _, s, _e = _der(der, i)      # OCTET STRING holding the RSA key
        _, s, _e = _der(der, s)      # its SEQUENCE
    _, _, i = _der(der, s)           # version
    nums = []
    for _ in range(3):               # n, e, d
        _, a, b = _der(der, i); nums.append(int.from_bytes(der[a:b], "big")); i = b
    return nums[0], nums[2]

def rs256_sign(pem, msg):
    n, d = rsa_key_from_pem(pem)
    k = (n.bit_length() + 7) // 8
    info = bytes.fromhex("3031300d060960864801650304020105000420") + hashlib.sha256(msg).digest()
    em = b"\x00\x01" + b"\xff" * (k - len(info) - 3) + b"\x00" + info
    return pow(int.from_bytes(em, "big"), d, n).to_bytes(k, "big")

_fcm_cache = {"tok": None, "exp": 0.0}

def fcm_conf():
    raw = os.environ.get("FIREBASE_SERVICE_ACCOUNT", "").strip()
    if not raw: return None
    try: j = json.loads(raw)
    except ValueError: return None
    return j if all(j.get(k) for k in ("project_id", "client_email", "private_key")) else None

def fcm_access_token(cf):
    if _fcm_cache["tok"] and time.time() < _fcm_cache["exp"] - 60: return _fcm_cache["tok"]
    b64 = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=").decode()
    now = int(time.time())
    signing = b64(json.dumps({"alg": "RS256", "typ": "JWT"}).encode()) + "." + b64(json.dumps({
        "iss": cf["client_email"], "scope": "https://www.googleapis.com/auth/firebase.messaging",
        "aud": "https://oauth2.googleapis.com/token", "iat": now, "exp": now + 3600}).encode())
    jwt = signing + "." + b64(rs256_sign(cf["private_key"], signing.encode()))
    body = urllib.parse.urlencode({"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": jwt}).encode()
    r = json.loads(urllib.request.urlopen(urllib.request.Request("https://oauth2.googleapis.com/token", data=body), timeout=20).read())
    _fcm_cache["tok"], _fcm_cache["exp"] = r["access_token"], time.time() + int(r.get("expires_in", 3600))
    return _fcm_cache["tok"]

def fcm_send(cf, token, title, body, data):
    msg = {"message": {"token": token, "notification": {"title": title, "body": body},
                       "data": {k: str(v) for k, v in data.items()},
                       "android": {"priority": "HIGH", "notification": {"channel_id": "viora_activity"}}}}
    req = urllib.request.Request(f"https://fcm.googleapis.com/v1/projects/{cf['project_id']}/messages:send", data=json.dumps(msg).encode(),
                                 headers={"Authorization": "Bearer " + fcm_access_token(cf), "Content-Type": "application/json"})
    try:
        urllib.request.urlopen(req, timeout=20).read(); return "ok"
    except urllib.error.HTTPError as e:
        err = e.read()[:400]
        print("push error:", e.code, err, flush=True)
        return "gone" if e.code == 404 or b"UNREGISTERED" in err else "error"

def push_user(user_id, title, body, data):
    cf = fcm_conf()
    if not cf: return
    try:
        with db() as c:
            tokens = [r["token"] for r in c.execute("SELECT token FROM devices WHERE user_id=?", (user_id,)).fetchall()]
        for t in tokens:
            if fcm_send(cf, t, title, body, data) == "gone":   # app uninstalled or token replaced
                with db() as c: c.execute("DELETE FROM devices WHERE token=?", (t,))
    except Exception:
        traceback.print_exc()

def push_async(user_id, title, body, data):
    if fcm_conf(): threading.Thread(target=push_user, args=(user_id, title, body, data), daemon=True).start()

def notify(c, user_id, actor, kind, text, post_id=None):
    """Adds an Activity entry and sends a push. Skips your own actions and repeated likes/follows."""
    user_id = int(user_id)
    if user_id == actor["id"]: return
    if kind in ("like", "follow", "request") and c.execute("SELECT 1 FROM notifications WHERE user_id=? AND actor_id=? AND kind=? AND COALESCE(post_id,0)=?",
                                               (user_id, actor["id"], kind, post_id or 0)).fetchone(): return
    c.execute("INSERT INTO notifications(user_id,actor_id,kind,post_id,text,created) VALUES(?,?,?,?,?,?)",
              (user_id, actor["id"], kind, post_id, text, time.time()))
    push_async(user_id, "Viora", f"{actor['username']} {text}",
               {"viora_type": kind, "viora_post": post_id or "", "viora_user": actor["username"]})

@route("POST", "/api/devices")
def register_device(h, q):
    tok = str(h.json_body().get("token", "")).strip()
    if not 20 <= len(tok) <= 500: raise HTTPError(400, "Bad token")
    with db() as c:
        u = h.user(c)
        c.execute("DELETE FROM devices WHERE token=?", (tok,))
        c.execute("INSERT INTO devices(token,user_id,created) VALUES(?,?,?)", (tok, u["id"], time.time()))
        c.execute("DELETE FROM devices WHERE user_id=? AND token NOT IN (SELECT token FROM devices WHERE user_id=? ORDER BY created DESC LIMIT 5)", (u["id"], u["id"]))
    h.send_json(200, {"ok": True})

@route("POST", "/api/devices/remove")
def remove_device(h, q):
    tok = str(h.json_body().get("token", "")).strip()
    with db() as c:
        u = h.user(c)
        c.execute("DELETE FROM devices WHERE token=? AND user_id=?", (tok, u["id"]))
    h.send_json(200, {"ok": True})

@route("GET", "/api/notifications")
def list_notifications(h, q):
    with db() as c:
        u = h.user(c)
        rows = c.execute("SELECT n.id, n.kind, n.post_id, n.text, n.created, n.seen, a.username, a.avatar FROM notifications n "
                         "JOIN users a ON a.id=n.actor_id WHERE n.user_id=? ORDER BY n.id DESC LIMIT 50", (u["id"],)).fetchall()
        out = [{"id": r["id"], "kind": r["kind"], "post_id": r["post_id"], "text": r["text"] or "", "created": int(r["created"] or 0),
                "new": not r["seen"], "user": r["username"], "avatar": h.url(r["avatar"])} for r in rows]
        c.execute("UPDATE notifications SET seen=1 WHERE user_id=? AND seen=0", (u["id"],))
    h.send_json(200, {"notifications": out})

# ---------- Direct messages (1:1 text; the app polls, new messages also arrive as pushes) ----------
DM_MAX_LEN = 1000

def dm_json(r, me):
    return {"id": r["id"], "mine": r["sender_id"] == me, "text": r["text"], "created": int(r["created"] or 0)}

@route("GET", "/api/messages/unread")
def dm_unread(h, q):
    with db() as c:
        u = h.user(c)
        n = c.execute("SELECT COUNT(*) AS n FROM messages WHERE recipient_id=? AND seen=0", (u["id"],)).fetchone()["n"]
        a = c.execute("SELECT COUNT(*) AS n FROM notifications WHERE user_id=? AND seen=0", (u["id"],)).fetchone()["n"]
    h.send_json(200, {"count": int(n), "activity": int(a)})

@route("GET", "/api/messages")
def dm_inbox(h, q):
    with db() as c:
        me = h.user(c)["id"]
        rows = c.execute(
            "SELECT other, MAX(id) AS last_id, SUM(CASE WHEN recipient_id=? AND seen=0 THEN 1 ELSE 0 END) AS unread FROM "
            "(SELECT m.id, m.recipient_id, m.seen, CASE WHEN m.sender_id=? THEN m.recipient_id ELSE m.sender_id END AS other "
            "FROM messages m WHERE (m.sender_id=? OR m.recipient_id=?) AND m.id > COALESCE((SELECT d.upto FROM dm_cleared d WHERE d.user_id=? "
            "AND d.other_id=(CASE WHEN m.sender_id=? THEN m.recipient_id ELSE m.sender_id END)), 0)) t "
            "GROUP BY other ORDER BY last_id DESC LIMIT 100", (me, me, me, me, me, me)).fetchall()
        out = []
        for r in rows:
            m = c.execute("SELECT * FROM messages WHERE id=?", (r["last_id"],)).fetchone()
            o = c.execute("SELECT username, avatar FROM users WHERE id=?", (r["other"],)).fetchone()
            if not m or not o: continue
            out.append({"user": o["username"], "avatar": h.url(o["avatar"]), "text": m["text"], "mine": m["sender_id"] == me,
                        "created": int(m["created"] or 0), "unread": int(r["unread"] or 0)})
    h.send_json(200, {"conversations": out})

@route("GET", r"/api/messages/with/([A-Za-z0-9_.]{3,20})")
def dm_thread(h, q, name):
    try: after = int(q.get("after", ["0"])[0])
    except ValueError: after = 0
    with db() as c:
        me = h.user(c)["id"]; t = target_user(c, name)
        cleared = c.execute("SELECT upto FROM dm_cleared WHERE user_id=? AND other_id=?", (me, t["id"])).fetchone()
        floor = max(after, int(cleared["upto"]) if cleared else 0)   # messages you deleted stay hidden
        rows = c.execute("SELECT * FROM messages WHERE ((sender_id=? AND recipient_id=?) OR (sender_id=? AND recipient_id=?)) AND id>? "
                         "ORDER BY id DESC LIMIT 100", (me, t["id"], t["id"], me, floor)).fetchall()
        c.execute("UPDATE messages SET seen=1 WHERE recipient_id=? AND sender_id=? AND seen=0", (me, t["id"]))
    h.send_json(200, {"messages": [dm_json(r, me) for r in reversed(rows)],
                      "user": {"username": t["username"], "avatar": h.url(t["avatar"])}})

@route("DELETE", r"/api/messages/with/([A-Za-z0-9_.]{3,20})")
def dm_delete(h, q, name):
    """Deletes the chat on the caller's side only. The other person keeps theirs; new messages bring the chat back."""
    with db() as c:
        me = h.user(c)["id"]; t = target_user(c, name)
        last = c.execute("SELECT COALESCE(MAX(id), 0) AS m FROM messages WHERE (sender_id=? AND recipient_id=?) OR (sender_id=? AND recipient_id=?)",
                         (me, t["id"], t["id"], me)).fetchone()["m"]
        c.execute("INSERT INTO dm_cleared(user_id,other_id,upto) VALUES(?,?,?) ON CONFLICT(user_id, other_id) DO UPDATE SET upto=excluded.upto",
                  (me, t["id"], int(last)))
        c.execute("UPDATE messages SET seen=1 WHERE recipient_id=? AND sender_id=?", (me, t["id"]))
    h.send_json(200, {"ok": True})

@route("POST", r"/api/messages/to/([A-Za-z0-9_.]{3,20})")
def dm_send(h, q, name):
    text = str(h.json_body().get("text", "")).strip()
    if not text: raise HTTPError(400, "Write a message first")
    if len(text) > DM_MAX_LEN: raise HTTPError(400, f"Messages can be at most {DM_MAX_LEN} characters")
    with db() as c:
        u = h.user(c); t = target_user(c, name)
        if t["id"] == u["id"]: raise HTTPError(400, "You can't message yourself")
        now = time.time()
        if c.execute("SELECT COUNT(*) AS n FROM messages WHERE sender_id=? AND created>?", (u["id"], now - 60)).fetchone()["n"] >= 30:
            raise HTTPError(429, "You're sending messages too fast. Wait a moment.")
        mid = c.execute("INSERT INTO messages(sender_id,recipient_id,text,created) VALUES(?,?,?,?) RETURNING id",
                        (u["id"], t["id"], text, now)).fetchone()["id"]
    if re.fullmatch(r"https?://\S+/p/\d+", text): body = "shared a post with you"
    elif re.fullmatch(r"https?://\S+/u/[A-Za-z0-9_.]+", text): body = "shared a profile with you"
    else: body = text[:120]
    push_async(t["id"], u["username"], body, {"viora_type": "message", "viora_user": u["username"]})
    h.send_json(201, {"message": {"id": mid, "mine": True, "text": text, "created": int(now)}})

@route("POST", r"/api/users/([A-Za-z0-9_.]{3,20})/follow")
def follow(h, q, name):
    with db() as c:
        me = h.user(c); t = target_user(c, name)
        if t["id"] == me["id"]: raise HTTPError(400, "You can't follow yourself")
        already = c.execute("SELECT 1 FROM follows WHERE follower_id=? AND followee_id=?", (me["id"], t["id"])).fetchone() is not None
        requested = False
        if t["is_private"] and not already:   # private accounts approve followers first
            added = c.execute("INSERT INTO follow_requests(requester_id,target_id,created) VALUES(?,?,?) ON CONFLICT DO NOTHING", (me["id"], t["id"], time.time())).rowcount
            if added: notify(c, t["id"], me, "request", "requested to follow you")
            requested = True
        else:
            added = c.execute("INSERT INTO follows(follower_id,followee_id,created) VALUES(?,?,?) ON CONFLICT DO NOTHING", (me["id"], t["id"], time.time())).rowcount
            if added: notify(c, t["id"], me, "follow", "started following you")
        n = follower_count(c, t["id"])
    h.send_json(200, {"following": not requested, "requested": requested, "followers": n})

@route("DELETE", r"/api/users/([A-Za-z0-9_.]{3,20})/follow")
def unfollow(h, q, name):
    with db() as c:
        me = h.user(c); t = target_user(c, name)
        c.execute("DELETE FROM follows WHERE follower_id=? AND followee_id=?", (me["id"], t["id"]))
        c.execute("DELETE FROM follow_requests WHERE requester_id=? AND target_id=?", (me["id"], t["id"]))   # also cancels a pending request
        n = follower_count(c, t["id"])
    h.send_json(200, {"following": False, "requested": False, "followers": n})

@route("GET", r"/api/users/([A-Za-z0-9_.]{3,20})/(followers|following)")
def follow_list(h, q, name, kind):
    with db() as c:
        me = h.user(c); t = target_user(c, name)
        if t["is_private"] and t["id"] != me["id"] and not c.execute("SELECT 1 FROM follows WHERE follower_id=? AND followee_id=?", (me["id"], t["id"])).fetchone():
            raise HTTPError(403, "This account is private")
        if kind == "following" and t["id"] != me["id"]:
            mode = t["following_privacy"] or "everyone"
            if mode == "me":
                raise HTTPError(403, "This person keeps who they follow private")
            if mode == "followers" and not c.execute("SELECT 1 FROM follows WHERE follower_id=? AND followee_id=?", (me["id"], t["id"])).fetchone():
                raise HTTPError(403, "Only followers can see who this person follows")
        if kind == "followers":
            sql = "SELECT u.username, u.bio, u.avatar FROM follows f JOIN users u ON u.id=f.follower_id WHERE f.followee_id=? ORDER BY f.created DESC LIMIT 200"
        else:
            sql = "SELECT u.username, u.bio, u.avatar FROM follows f JOIN users u ON u.id=f.followee_id WHERE f.follower_id=? ORDER BY f.created DESC LIMIT 200"
        rows = c.execute(sql, (t["id"],)).fetchall()
    h.send_json(200, {"users": [{"username": r["username"], "bio": r["bio"], "avatar": h.url(r["avatar"])} for r in rows]})

@route("GET", "/api/posts")
def list_posts(h, q):
    with db() as c: h.send_json(200, {"posts": h.post_json(c, h.user(c)["id"])})

@route("GET", "/api/feed/following")
def following_feed(h, q):
    """Posts from people the user follows, plus their own, newest first."""
    with db() as c:
        uid = h.user(c)["id"]
        posts = h.post_json(c, uid, "WHERE (p.user_id=? OR p.user_id IN (SELECT followee_id FROM follows WHERE follower_id=?))", (uid, uid))
    h.send_json(200, {"posts": posts})

@route("POST", "/api/posts")
def create_post(h, q):
    caption = (q.get("caption", [""])[0])[:2200]
    kind = q.get("kind", ["image"])[0]
    if kind not in ("image", "video"): raise HTTPError(400, "Unknown post type")
    with db() as c:
        u = h.user(c); raw = h.body(); thumb = None
        if kind == "video":
            if len(raw) > MAX_VIDEO_BYTES: raise HTTPError(413, f"Video is too large (max {MAX_VIDEO_BYTES // 1024 // 1024} MB)")
            if raw[4:8] != b"ftyp": raise HTTPError(400, "Please upload an MP4 video")
            frames, _ = video_frames(raw)
            moderate_images([(f, "image/jpeg") for f in frames])
            thumb = store_media(c, frames[len(frames) // 2], "image/jpeg")
            name = store_video(c, raw)
        else:
            name = save_image(c, raw)
        try:
            pid = c.execute("INSERT INTO posts(user_id,image,caption,created,kind,thumb) VALUES(?,?,?,?,?,?) RETURNING id",
                            (u["id"], name, caption, time.time(), kind, thumb)).fetchone()["id"]
            post = h.post_json(c, u["id"], "WHERE p.id=?", (pid,))[0]
        except Exception:
            if str(name).startswith("s3:"): s3_delete_quiet(name)  # don't leave an orphaned file in the bucket
            raise
    h.send_json(201, {"post": post})

def own_post(c, u, pid):
    p = c.execute("SELECT * FROM posts WHERE id=?", (pid,)).fetchone()
    if not p: raise HTTPError(404, "Post not found")
    if p["user_id"] != u["id"]: raise HTTPError(403, "Not your post")
    return p

@route("GET", r"/api/posts/(\d+)")
def get_post(h, q, pid):
    with db() as c:
        u = h.user(c)
        posts = h.post_json(c, u["id"], "WHERE p.id=?", (int(pid),))
    if not posts: raise HTTPError(404, "Post not found")
    h.send_json(200, {"post": posts[0]})

SHARE_CSS = ("body{font-family:sans-serif;margin:0;background:#fafafa;color:#111}"
             ".c{max-width:480px;margin:0 auto;background:#fff;min-height:100vh}"
             ".h{padding:12px 16px;font-weight:bold}"
             "img,video{width:100%;display:block}"
             ".t{padding:12px 16px;white-space:pre-wrap;word-break:break-word}"
             ".b{display:block;margin:16px;padding:14px;text-align:center;background:#6200ee;color:#fff;"
             "text-decoration:none;border-radius:8px;font-weight:bold}"
             ".s{color:#777;font-size:13px;text-align:center;padding:0 16px 24px}")

@route("GET", r"/u/([A-Za-z0-9_.]{3,20})")
def user_page(h, q, name):
    """Public landing page for a shared profile."""
    esc = htmllib.escape
    with db() as c:
        t = c.execute("SELECT username, bio, avatar FROM users WHERE LOWER(username)=LOWER(?)", (name,)).fetchone()
    if not t:
        return h.send_html(404, "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
                                "<body style='font-family:sans-serif;text-align:center;padding:3rem 1rem'>"
                                "<h2>User not found</h2><p>This account may no longer exist.</p>")
    base = f"{h.headers.get('X-Forwarded-Proto', 'http')}://{h.headers.get('Host', 'localhost')}"
    title, desc, pic = f"{t['username']} on Viora", (t["bio"] or "")[:200], h.url(t["avatar"])
    head = ("<!doctype html><html><head><meta charset=utf-8>"
            "<meta name=viewport content='width=device-width,initial-scale=1'>"
            f"<title>{esc(title)}</title>"
            f"<meta property=og:title content=\"{esc(title)}\">"
            f"<meta property=og:description content=\"{esc(desc)}\">"
            f"<meta property=og:url content=\"{esc(base)}/u/{esc(t['username'])}\">"
            + (f"<meta property=og:image content=\"{esc(pic)}\">" if pic else "")
            + f"<style>{SHARE_CSS}</style></head>")
    avatar = (f"<img src=\"{esc(pic)}\" alt=\"\" style='width:120px;height:120px;border-radius:50%;object-fit:cover;margin:24px auto 8px'>" if pic else
              f"<div style='width:120px;height:120px;border-radius:50%;background:#8134af;color:#fff;font-size:48px;line-height:120px;margin:24px auto 8px'>{esc(t['username'][:1].upper())}</div>")
    body = (f"<body><div class=c style='text-align:center'>{avatar}<div class=h style='font-size:20px'>{esc(t['username'])}</div>"
            + (f"<div class=t>{esc(t['bio'])}</div>" if t["bio"] else "")
            + f"<a class=b href=\"viora://u/{esc(t['username'])}\">Open in Viora</a>"
            "<div class=s>Install the Viora app to follow and message.</div></div></body></html>")
    h.send_html(200, head + body)

@route("GET", r"/p/(\d+)")
def share_page(h, q, pid):
    """Public landing page for a shared post, with link-preview tags and an Open in Viora button."""
    esc = htmllib.escape
    with db() as c:
        posts = h.post_json(c, 0, "WHERE p.id=?", (int(pid),))
    if not posts:
        return h.send_html(404, "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
                                "<body style='font-family:sans-serif;text-align:center;padding:3rem 1rem'>"
                                "<h2>Post not found</h2><p>It may have been deleted.</p>")
    p = posts[0]
    base = f"{h.headers.get('X-Forwarded-Proto', 'http')}://{h.headers.get('Host', 'localhost')}"
    title = f"{p['user']} on Viora"
    desc = (p["caption"] or "")[:200]
    pic = p["thumb"] if p["kind"] == "video" else p["image"]
    head = ("<!doctype html><html><head><meta charset=utf-8>"
            "<meta name=viewport content='width=device-width,initial-scale=1'>"
            f"<title>{esc(title)}</title>"
            f"<meta property=og:title content=\"{esc(title)}\">"
            f"<meta property=og:description content=\"{esc(desc)}\">"
            f"<meta property=og:url content=\"{esc(base)}/p/{p['id']}\">"
            + (f"<meta property=og:image content=\"{esc(pic)}\"><meta name=twitter:card content=summary_large_image>" if pic else "")
            + f"<style>{SHARE_CSS}</style></head>")
    if p["kind"] == "video":
        media = f"<video controls playsinline preload=metadata src=\"{esc(p['image'])}\"" + (f" poster=\"{esc(p['thumb'])}\"" if p["thumb"] else "") + "></video>"
    else:
        media = f"<img src=\"{esc(p['image'])}\" alt=\"\">"
    body = (f"<body><div class=c><div class=h>{esc(p['user'])}</div>{media}"
            + (f"<div class=t>{esc(p['caption'])}</div>" if p["caption"] else "")
            + f"<a class=b href=\"viora://p/{p['id']}\">Open in Viora</a>"
            "<div class=s>Install the Viora app to like, comment and follow.</div></div></body></html>")
    h.send_html(200, head + body)

@route("GET", "/api/boost/plans")
def boost_plans_route(h, q):
    pid = q.get("post", [""])[0]
    status = None
    with db() as c:
        u = h.user(c)
        if pid.isdigit() and c.execute("SELECT 1 FROM posts WHERE id=? AND user_id=?", (int(pid), u["id"])).fetchone():
            r = c.execute("SELECT MAX(ends) AS e, COALESCE(SUM(views),0) AS v FROM boosts WHERE post_id=?", (int(pid),)).fetchone()
            if r["e"]: status = {"until": int(r["e"]), "active": float(r["e"]) > time.time(), "views": int(r["v"])}
    h.send_json(200, {"plans": [{"days": p["days"], "price": p["price"]} for p in boost_plans()], "status": status})

@route("POST", "/api/boost/checkout")
def boost_checkout(h, q):
    d = h.json_body()
    try: pid, days = int(d.get("post_id")), int(d.get("days"))
    except (TypeError, ValueError): raise HTTPError(400, "Bad request")
    plan = next((p for p in boost_plans() if p["days"] == days), None)
    if not plan: raise HTTPError(400, "That boost option isn't available")
    with db() as c:
        u = h.user(c)
        if not c.execute("SELECT 1 FROM posts WHERE id=? AND user_id=?", (pid, u["id"])).fetchone(): raise HTTPError(404, "Post not found")
        if u["is_private"]: raise HTTPError(400, "Private accounts can't boost posts. Switch to a public account first.")
        email = u["email"] if u["email_verified"] else None
    params = {"mode": "payment", "line_items[0][quantity]": "1",
              "line_items[0][price_data][currency]": boost_currency(),
              "line_items[0][price_data][unit_amount]": str(plan["cents"]),
              "line_items[0][price_data][product_data][name]": f"Viora boost: {days} day{'s' if days != 1 else ''}",
              "client_reference_id": str(u["id"]), "metadata[boost_post_id]": str(pid), "metadata[boost_days]": str(days),
              "metadata[user_id]": str(u["id"]),
              "success_url": h.origin() + "/billing/return", "cancel_url": h.origin() + "/billing/return"}
    if email: params["customer_email"] = email
    h.send_json(200, {"url": stripe_api("POST", "/v1/checkout/sessions", params)["url"]})

@route("POST", "/api/admin/migrate-videos")
def migrate_videos(h, q):
    """Moves a few existing database-stored videos to the bucket per call. Call repeatedly until remaining is 0."""
    key = os.environ.get("ADMIN_KEY", "")
    if not key or not hmac.compare_digest(h.headers.get("X-Admin-Key", "").encode(), key.encode()):
        raise HTTPError(403, "Forbidden")
    if not s3_conf(): raise HTTPError(503, "Set the S3_* variables on the server first")
    moved = 0
    with db() as c:
        rows = c.execute("SELECT id, image FROM posts WHERE kind='video' AND SUBSTR(image,1,3)<>'s3:' ORDER BY id LIMIT 3").fetchall()
        for r in rows:
            if not str(r["image"]).isdigit(): continue
            m = c.execute("SELECT data FROM images WHERE id=?", (int(r["image"]),)).fetchone()
            if not m: continue
            name = store_video(c, bytes(m["data"]))
            c.execute("UPDATE posts SET image=? WHERE id=?", (name, r["id"]))
            c.execute("DELETE FROM images WHERE id=?", (int(r["image"]),))
            moved += 1
        left = c.execute("SELECT COUNT(*) AS n FROM posts WHERE kind='video' AND SUBSTR(image,1,3)<>'s3:'").fetchone()["n"]
    h.send_json(200, {"moved": moved, "remaining": int(left)})

@route("POST", "/api/admin/boost")
def admin_boost(h, q):
    key = os.environ.get("ADMIN_KEY", "")
    if not key or not hmac.compare_digest(h.headers.get("X-Admin-Key", "").encode(), key.encode()):
        raise HTTPError(403, "Forbidden")
    d = h.json_body()
    try: pid, days = int(d.get("post_id")), int(d.get("days", 1))
    except (TypeError, ValueError): raise HTTPError(400, "Bad request")
    if not 1 <= days <= 365: raise HTTPError(400, "days must be 1-365")
    with db() as c:
        p = c.execute("SELECT user_id FROM posts WHERE id=?", (pid,)).fetchone()
        if not p: raise HTTPError(404, "Post not found")
        activate_boost(c, pid, p["user_id"], days)
    h.send_json(200, {"ok": True, "post_id": pid, "days": days})

@route("GET", "/api/ads")
def ads(h, q):
    """Active boosted posts from other people, shuffled. VIP members see no ads."""
    with db() as c:
        u = h.user(c)
        if vip_active(u): return h.send_json(200, {"posts": []})
        rows = c.execute("SELECT DISTINCT post_id FROM boosts WHERE ends>? AND user_id<>? AND user_id NOT IN (SELECT id FROM users WHERE is_private=1)", (time.time(), u["id"])).fetchall()
        ids = [r["post_id"] for r in rows]
        random.shuffle(ids)
        posts = []
        for i in ids[:5]: posts += h.post_json(c, u["id"], "WHERE p.id=?", (i,))
    h.send_json(200, {"posts": posts})

@route("POST", "/api/posts/seen")
def posts_seen(h, q):
    """Counts each person once per post; the owner's own views don't count."""
    raw = h.json_body().get("ids")
    if not isinstance(raw, list): raise HTTPError(400, "Bad request")
    ids = {int(x) for x in raw[:50] if isinstance(x, int) or (isinstance(x, str) and x.isdigit())}
    with db() as c:
        u = h.user(c)
        for pid in ids:
            c.execute("INSERT INTO post_views(post_id,user_id,created) SELECT id,?,? FROM posts WHERE id=? AND user_id<>? ON CONFLICT DO NOTHING",
                      (u["id"], time.time(), pid, u["id"]))
    h.send_json(200, {"ok": True})

@route("POST", "/api/ads/seen")
def ad_seen(h, q):
    try: pid = int(h.json_body().get("post_id"))
    except (TypeError, ValueError): raise HTTPError(400, "Bad request")
    with db() as c:
        u = h.user(c)
        c.execute("UPDATE boosts SET views=views+1 WHERE id=(SELECT id FROM boosts WHERE post_id=? AND ends>? AND user_id<>? ORDER BY ends DESC LIMIT 1)",
                  (pid, time.time(), u["id"]))
    h.send_json(200, {"ok": True})

@route("PUT", r"/api/posts/(\d+)")
def edit_post(h, q, pid):
    d = h.json_body()
    with db() as c:
        u = h.user(c); own_post(c, u, pid)
        c.execute("UPDATE posts SET caption=? WHERE id=?", (str(d.get("caption", ""))[:2200], pid))
    h.send_json(200, {"ok": True})

@route("DELETE", r"/api/posts/(\d+)")
def delete_post(h, q, pid):
    with db() as c:
        u = h.user(c); p = own_post(c, u, pid)
        c.execute("DELETE FROM posts WHERE id=?", (pid,)); remove_image(c, p["image"]); remove_image(c, p["thumb"])
    h.send_json(200, {"ok": True})

@route("POST", r"/api/posts/(\d+)/like")
def like(h, q, pid):
    with db() as c:
        u = h.user(c)
        if not post_visible(c, u["id"], pid): raise HTTPError(404, "Post not found")
        if c.execute("DELETE FROM likes WHERE post_id=? AND user_id=?", (pid, u["id"])).rowcount: liked = False
        else:
            c.execute("INSERT INTO likes(post_id,user_id) VALUES(?,?)", (pid, u["id"])); liked = True
            owner = c.execute("SELECT user_id FROM posts WHERE id=?", (pid,)).fetchone()["user_id"]
            notify(c, owner, u, "like", "liked your post", int(pid))
        n = c.execute("SELECT COUNT(*) AS n FROM likes WHERE post_id=?", (pid,)).fetchone()["n"]
    h.send_json(200, {"liked": liked, "likes": n})

def comment_json(h, r):
    return {"user": r["username"], "avatar": h.url(r["avatar"]), "text": r["text"], "created": int(r["created"] or 0)}

@route("GET", r"/api/posts/(\d+)/comments")
def list_comments(h, q, pid):
    with db() as c:
        if not post_visible(c, h.user(c)["id"], pid): raise HTTPError(404, "Post not found")
        rows = c.execute("""SELECT c.text, c.created, u.username, u.avatar FROM comments c JOIN users u ON u.id=c.user_id
                            WHERE c.post_id=? ORDER BY c.id""", (pid,)).fetchall()
    h.send_json(200, {"comments": [comment_json(h, r) for r in rows]})

@route("POST", r"/api/posts/(\d+)/comments")
def add_comment(h, q, pid):
    text = str(h.json_body().get("text", "")).strip()[:500]
    if not text: raise HTTPError(400, "Comment is empty")
    with db() as c:
        u = h.user(c)
        if not post_visible(c, u["id"], pid): raise HTTPError(404, "Post not found")
        now = time.time()
        c.execute("INSERT INTO comments(post_id,user_id,text,created) VALUES(?,?,?,?)", (pid, u["id"], text, now))
        owner = c.execute("SELECT user_id FROM posts WHERE id=?", (pid,)).fetchone()["user_id"]
        notify(c, owner, u, "comment", "commented: " + text[:80], int(pid))
    h.send_json(201, {"comment": {"user": u["username"], "avatar": h.url(u["avatar"]), "text": text, "created": int(now)}})

@route("GET", "/api/stories")
def list_stories(h, q):
    with db() as c:
        me = h.user(c)["id"]   # only your own stories and those of people you follow
        rows = c.execute("""SELECT s.image, u.username, u.avatar FROM stories s JOIN users u ON u.id=s.user_id
                            WHERE s.created>? AND (s.user_id=? OR s.user_id IN (SELECT followee_id FROM follows WHERE follower_id=?))
                            ORDER BY s.id""", (time.time() - STORY_TTL, me, me)).fetchall()
    out = {}
    for r in rows:
        g = out.setdefault(r["username"], {"user": r["username"], "avatar": h.url(r["avatar"]), "images": []})
        g["images"].append(h.url(r["image"]))
    h.send_json(200, {"stories": list(out.values())})

@route("POST", "/api/stories")
def create_story(h, q):
    with db() as c:
        u = h.user(c); name = save_image(c, h.body())
        c.execute("INSERT INTO stories(user_id,image,created) VALUES(?,?,?)", (u["id"], name, time.time()))
    h.send_json(201, {"ok": True})

if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8000"))
    print(f"Viora server on port {port}  (storage: {'PostgreSQL' if PG else os.path.abspath(DB_FILE)})")
    ThreadingHTTPServer(("0.0.0.0", port), H).serve_forever()
