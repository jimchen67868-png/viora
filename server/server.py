#!/usr/bin/env python3
"""Viora server: REST API + image hosting. Pure Python 3.8+ standard library, SQLite storage.
Run:  python3 server.py   (env: PORT=8000, DATABASE_URL=postgres://... or VIORA_DATA=./data for SQLite)
"""
import hashlib, hmac, json, math, os, re, secrets, sqlite3, time, traceback, urllib.request
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
MAX_BODY = 12 * 1024 * 1024
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
for _col, _typ in (("email", "TEXT"), ("email_verified", "INTEGER NOT NULL DEFAULT 0"), ("is_vip", "INTEGER NOT NULL DEFAULT 0")):
    try:  # add columns to databases created before email support existed
        with db() as _c: _c.execute(f"ALTER TABLE users ADD COLUMN {'IF NOT EXISTS ' if PG else ''}{_col} {_typ}")
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

def save_image(c, b):
    ctype = sniff(b)
    if not ctype: raise HTTPError(400, "Unsupported or empty image")
    data = psycopg2.Binary(b) if PG else b
    return str(c.execute("INSERT INTO images(ctype,data) VALUES(?,?) RETURNING id", (ctype, data)).fetchone()["id"])

def remove_image(c, name):
    if name and str(name).isdigit(): c.execute("DELETE FROM images WHERE id=?", (int(name),))

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

    def url(self, f):
        if not f: return None
        proto = self.headers.get("X-Forwarded-Proto", "http")
        return f"{proto}://{self.headers.get('Host', 'localhost')}/uploads/{f}"

    def profile(self, u):
        return {"username": u["username"], "bio": u["bio"], "avatar": self.url(u["avatar"]),
                "email": u["email"], "email_verified": bool(u["email_verified"]), "vip": bool(u["is_vip"])}

    def post_json(self, c, uid, where="", args=(), limit=100):
        rows = c.execute(f"""SELECT p.id, p.caption, p.image, u.username, u.avatar,
            (SELECT COUNT(*) FROM likes WHERE post_id=p.id) AS likes,
            EXISTS(SELECT 1 FROM likes WHERE post_id=p.id AND user_id=?) AS liked,
            (SELECT COUNT(*) FROM comments WHERE post_id=p.id) AS comments
            FROM posts p JOIN users u ON u.id=p.user_id {where} ORDER BY p.id DESC LIMIT {int(limit)}""", (uid, *args)).fetchall()
        return [{"id": r["id"], "user": r["username"], "avatar": self.url(r["avatar"]), "image": self.url(r["image"]),
                 "caption": r["caption"], "likes": r["likes"], "liked": bool(r["liked"]), "comments": r["comments"]} for r in rows]

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
        with db() as c:
            r = c.execute("SELECT ctype, data FROM images WHERE id=?", (int(name),)).fetchone()
        if not r: raise HTTPError(404, "Not found")
        data = bytes(r["data"])
        self.send_response(200)
        self.send_header("Content-Type", r["ctype"])
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "public, max-age=31536000, immutable")
        self.end_headers()
        self.wfile.write(data)

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
    # public view: never include the email
    h.send_json(200, {"user": {"username": u["username"], "bio": u["bio"], "avatar": h.url(u["avatar"])}, "posts": posts})

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
        if not u["is_vip"]: raise HTTPError(403, "For You is a VIP feature")
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

@route("GET", "/api/posts")
def list_posts(h, q):
    with db() as c: h.send_json(200, {"posts": h.post_json(c, h.user(c)["id"])})

@route("POST", "/api/posts")
def create_post(h, q):
    caption = (q.get("caption", [""])[0])[:2200]
    with db() as c:
        u = h.user(c); name = save_image(c, h.body())
        pid = c.execute("INSERT INTO posts(user_id,image,caption,created) VALUES(?,?,?,?) RETURNING id", (u["id"], name, caption, time.time())).fetchone()["id"]
        post = h.post_json(c, u["id"], "WHERE p.id=?", (pid,))[0]
    h.send_json(201, {"post": post})

def own_post(c, u, pid):
    p = c.execute("SELECT * FROM posts WHERE id=?", (pid,)).fetchone()
    if not p: raise HTTPError(404, "Post not found")
    if p["user_id"] != u["id"]: raise HTTPError(403, "Not your post")
    return p

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
        c.execute("DELETE FROM posts WHERE id=?", (pid,)); remove_image(c, p["image"])
    h.send_json(200, {"ok": True})

@route("POST", r"/api/posts/(\d+)/like")
def like(h, q, pid):
    with db() as c:
        u = h.user(c)
        if not c.execute("SELECT 1 FROM posts WHERE id=?", (pid,)).fetchone(): raise HTTPError(404, "Post not found")
        if c.execute("DELETE FROM likes WHERE post_id=? AND user_id=?", (pid, u["id"])).rowcount: liked = False
        else:
            c.execute("INSERT INTO likes(post_id,user_id) VALUES(?,?)", (pid, u["id"])); liked = True
        n = c.execute("SELECT COUNT(*) AS n FROM likes WHERE post_id=?", (pid,)).fetchone()["n"]
    h.send_json(200, {"liked": liked, "likes": n})

def comment_json(h, r):
    return {"user": r["username"], "avatar": h.url(r["avatar"]), "text": r["text"]}

@route("GET", r"/api/posts/(\d+)/comments")
def list_comments(h, q, pid):
    with db() as c:
        h.user(c)
        rows = c.execute("""SELECT c.text, u.username, u.avatar FROM comments c JOIN users u ON u.id=c.user_id
                            WHERE c.post_id=? ORDER BY c.id""", (pid,)).fetchall()
    h.send_json(200, {"comments": [comment_json(h, r) for r in rows]})

@route("POST", r"/api/posts/(\d+)/comments")
def add_comment(h, q, pid):
    text = str(h.json_body().get("text", "")).strip()[:500]
    if not text: raise HTTPError(400, "Comment is empty")
    with db() as c:
        u = h.user(c)
        if not c.execute("SELECT 1 FROM posts WHERE id=?", (pid,)).fetchone(): raise HTTPError(404, "Post not found")
        c.execute("INSERT INTO comments(post_id,user_id,text,created) VALUES(?,?,?,?)", (pid, u["id"], text, time.time()))
    h.send_json(201, {"comment": {"user": u["username"], "avatar": h.url(u["avatar"]), "text": text}})

@route("GET", "/api/stories")
def list_stories(h, q):
    with db() as c:
        h.user(c)
        rows = c.execute("""SELECT s.image, u.username, u.avatar FROM stories s JOIN users u ON u.id=s.user_id
                            WHERE s.created>? ORDER BY s.id""", (time.time() - STORY_TTL,)).fetchall()
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
