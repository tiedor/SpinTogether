# Spin Together

A small Java application for sharing live spinning wheels. The room owner creates categories and values, then spins; everyone with the six-character code sees each result and shared history.

Room state, owner access, wheels, and history are stored on the server while a room is in use. It remains available for a 15-minute reconnect window after the last person leaves, including if the service restarts. Export its JSON before then to use the room in a later session.

## Run locally

Install Java 21 and Maven, then from PowerShell run:

```powershell
mvn spring-boot:run
```

Without a database URL, the app uses a local H2 database at `./data/spintogether`. To test against Postgres instead, set `DATABASE_URL` to a standard `postgresql://user:password@host:5432/database` URL before starting it.

Then open `http://localhost:8080` in two browser windows. Create a room in one window and enter the code in the other.

## Deploy on Render

1. Create a Render Postgres database in the same region as the web service.
2. When the database status is **Available**, open its **Info** page and copy the **Internal Database URL**.
3. Open the `SpinTogether` web service, choose **Environment**, and add a secret environment variable:

   ```text
   DATABASE_URL=<the Internal Database URL>
   ```

4. Choose **Save and deploy**. The app creates its `live_rooms` table automatically on startup.

Use the internal URL only in Render. It is a secret and must not be committed to GitHub or pasted into the application source.

The free Render Postgres plan is intended for testing and prototyping and expires after 30 days. SpinTogether deletes inactive rooms after their 15-minute reconnect window, so use the JSON export to save a room between separate sessions.
