CREATE TABLE stock (
    sku       varchar(64) PRIMARY KEY,
    -- Last line of defence: even a buggy code path cannot sell stock we don't have
    available integer     NOT NULL CHECK (available >= 0)
);

CREATE TABLE reservation (
    id         uuid        PRIMARY KEY,
    sku        varchar(64) NOT NULL REFERENCES stock (sku),
    quantity   integer     NOT NULL CHECK (quantity > 0),
    status     varchar(16) NOT NULL,
    expires_at timestamptz NOT NULL
);

-- The expiry job only ever looks for active reservations past their deadline
CREATE INDEX reservation_active_expires_at ON reservation (expires_at) WHERE status = 'ACTIVE';
