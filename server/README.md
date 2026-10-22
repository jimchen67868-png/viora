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
