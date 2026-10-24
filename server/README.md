# Viora server
Python 3.8+. Storage is PostgreSQL if `DATABASE_URL` is set, otherwise a local SQLite file (`./data`).
Images are stored in the database, so the server keeps no files on disk.

    python3 server.py                 # local, SQLite
    DATABASE_URL=postgres://... python3 server.py   # needs: pip install -r requirements.txt

## Email (verification + password reset)
Sent through Brevo's HTTPS API (Render's free tier blocks SMTP ports). Environment variables:
- `BREVO_API_KEY` - Brevo -> SMTP & API -> API keys
- `MAIL_FROM`     - a sender address you verified in Brevo (Senders & IP -> Senders)
For local testing set `MAIL_DEBUG=1` and codes are printed to the server log instead of emailed.

## VIP users
Set `ADMIN_KEY` (any long secret) in the environment, then grant/revoke VIP from any terminal:

    curl -X POST https://YOUR-SERVER/api/admin/vip -H "X-Admin-Key: YOUR_KEY" \
         -H "Content-Type: application/json" -d '{"username":"alice","vip":true}'

VIPs get the "For you" feed: posts ranked by the authors, words and #hashtags of posts they liked.

## VIP subscriptions (Stripe Checkout)
Env: `STRIPE_SECRET_KEY` (sk_...), `STRIPE_WEBHOOK_SECRET` (whsec_...), `STRIPE_PRICE_MONTHLY` / `STRIPE_PRICE_YEARLY`
(price_... ids; set one or both), optional `PLAN_MONTHLY_TEXT` / `PLAN_YEARLY_TEXT` (label shown in the app).
Webhook endpoint: `https://YOUR-SERVER/api/stripe/webhook` with events
`checkout.session.completed`, `customer.subscription.created`, `customer.subscription.updated`, `customer.subscription.deleted`.
Enable the Customer Portal in the Stripe dashboard so subscribers can cancel/update their card ("Manage" button).

## Google Play subscriptions
Env: `GOOGLE_SERVICE_ACCOUNT_JSON` (full JSON key of a Google Cloud service account that has been granted access in Play Console
with "View financial data" + "Manage orders and subscriptions"), `GOOGLE_PLAY_PACKAGE` (the app's applicationId),
`GOOGLE_PLAY_PRODUCT_ID` (subscription product id, default `viora_vip`; base plans `monthly` / `yearly`).
The app sends the purchase token to `/api/billing/google/verify`; the server verifies it with the Play Developer API
(`purchases.subscriptionsv2.get`), grants VIP until the expiry time and acknowledges the purchase.
`/api/billing/google/sync` re-checks later so cancellations/refunds take effect.

## Photo moderation (no sexual content)
Set `OPENAI_API_KEY` and every uploaded photo (posts, stories, profile photos) is checked with OpenAI's free
`omni-moderation-latest` model before it is saved; photos scored as sexual are rejected (HTTP 422). Optional:
`MODERATION_THRESHOLD` (default 0.5; lower = stricter). If the check can't run, the upload is refused.
Without the key, moderation is off.

## Video posts
Clips (MP4, max 30 s / 20 MB, compressed on the phone) are stored in the database next to photos and streamed with byte-range support.
On upload the server samples 6 frames, runs them through the same photo moderation, and saves the middle frame as the poster.
Frame sampling uses PyAV (installed from requirements.txt) or, if PyAV is missing, the `ffmpeg`/`ffprobe` command-line tools.
Without either, video uploads are refused. Stories remain photo-only.
