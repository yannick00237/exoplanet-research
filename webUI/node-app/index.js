const express = require("express");
const mysql = require("mysql2/promise"); // Use promises for async/await
const path = require("path");

const app = express();
const port = 3000;

app.set("view engine", "ejs");
app.set("views", path.join(__dirname, "views"));
app.use(express.static(path.join(__dirname, "public")));

// Create a connection pool (recommended for performance)
const pool = mysql.createPool({
  host: "db",
  user: "root", // Use 'root' or your MariaDB user
  password: "mysecretpassword",
  database: "exoplanet",
  waitForConnections: true,
  connectionLimit: 10,
  queueLimit: 0,
});

async function connectToDatabase() {
  try {
    const connection = await pool.getConnection();
    console.log("Connected to the MariaDB database");
    connection.release(); // Release the connection back to the pool
  } catch (err) {
    console.error("Database connection error:", err);
  }
}

connectToDatabase();

app.get("/", (req, res) => {
  res.render("index", { message: "Welcome to the API!" });
});

app.get("/groundstations", async (req, res) => {
  try {
    const [rows] = await pool.query("SELECT * FROM GroundStation");
    res.render("groundstations", { groundstations: rows });
  } catch (err) {
    console.error("Query error:", err);
    res.status(500).send("Internal Server Error");
  }
});

app.get("/planets", async (req, res) => {
  try {
    const [rows] = await pool.query("SELECT * FROM Planet");
    res.render("planets", { planets: rows });
  } catch (err) {
    console.error("Query error:", err);
    res.status(500).send("Internal Server Error");
  }
});

app.get("/fields", async (req, res) => {
  try {
    const [rows] = await pool.query("SELECT * FROM Field");
    const gridSize = 10;

    let maxX = gridSize - 1;
    let maxY = gridSize - 1;

    let fieldArray = [];
    for (let x = 0; x < gridSize; x++) {
      for (let y = 0; y < gridSize; y++) {
        for (let i = 0; i < rows.length; i++) {
          var row = rows[i];
          if (row.positionX == x && row.positionY == y && row.planet_id == 1) {
            row.temperature = Math.round(100 * row.temperature) / 100;
            fieldArray.push(row);
            continue;
          }
        }
        fieldArray.push({ positionX: x, positionY: y });
      }
    }

    const scaleX = 1;
    const scaleY = 1;

    console.log(fieldArray);

    res.render("fields", {
      fields: fieldArray,
      maxX: maxX,
      maxY: maxY,
      scaleX: scaleX,
      scaleY: scaleY,
    });
  } catch (err) {
    console.error("Query error:", err);
    res.status(500).send("Internal Server Error");
  }
});

app.get("/robots", async (req, res) => {
  try {
    const [rows] = await pool.query("SELECT * FROM Robot");
    res.render("robots", { robots: rows });
  } catch (err) {
    console.error("Query error:", err);
    res.status(500).send("Internal Server Error");
  }
});

app.get("/measurements", async (req, res) => {
  try {
    const [rows] = await pool.query("SELECT * FROM Measurement");
    res.render("measurements", { measurements: rows });
  } catch (err) {
    console.error("Query error:", err);
    res.status(500).send("Internal Server Error");
  }
});

app.listen(port, () => {
  console.log(`Server listening at http://localhost:${port}`);
});

process.on("SIGINT", async () => {
  console.log("Closing database connection...");
  await pool.end();
  console.log("Database connection closed.");
  process.exit();
});
