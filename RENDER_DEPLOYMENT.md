# Deploy CampusTransit on Render

The Render blueprint in `render.yaml` deploys the Java backend and serves the frontend from the same web service. Render does not provide a MySQL service, so prepare an externally hosted MySQL database that Render can reach before deploying.

## 1. Prepare MySQL

Create a MySQL database and import [`smart_bus_db_dump.sql`](./smart_bus_db_dump.sql). Keep the database host, port, name, username, and password available for the Render environment settings.

## 2. Deploy the blueprint

1. Push the deployment changes to the GitHub repository.
2. In Render, create a **Blueprint** and connect `ppriyadharshinig-563/Miniproject`.
3. Select the `main` branch and use the repository's `render.yaml`.
4. Enter the MySQL connection values when Render requests the blueprint's environment variables:
   - `DB_HOST`
   - `DB_PORT` (defaults to `3306`)
   - `DB_NAME` (defaults to `smart_bus_db`)
   - `DB_USER`
   - `DB_PASSWORD`
5. Deploy the service. Render's assigned URL serves both the website and its `/api` endpoints.

If your database provider requires JDBC connection options such as TLS, set `MYSQL_URL` in the Render service environment to a JDBC URL such as `jdbc:mysql://HOST:PORT/DATABASE?sslMode=REQUIRED`. The separate `DB_USER` and `DB_PASSWORD` values are still used for authentication.

Check `https://YOUR-SERVICE.onrender.com/api/health` after deployment; it should return a JSON status of `ok`.

Do not commit database credentials. Rotate any database password that was previously stored in source code, and set the replacement only in Render's environment settings.
