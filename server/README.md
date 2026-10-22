# Viora server
Python 3.8+. Storage is PostgreSQL if `DATABASE_URL` is set, otherwise a local SQLite file (`./data`).
Images are stored in the database, so the server keeps no files on disk.

    python3 server.py                 # local, SQLite
    DATABASE_URL=postgres://... python3 server.py   # needs: pip install -r requirements.txt

Free hosting: Render (web service, free) + Neon (free Postgres). See `render.yaml`.
