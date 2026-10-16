CREATE TABLE stock_projection_lock (
    id INTEGER PRIMARY KEY CHECK (id = 1)
);

INSERT INTO stock_projection_lock (id) VALUES (1);
