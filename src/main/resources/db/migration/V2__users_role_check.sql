-- Enforce allowed roles at the database level (matches Java enum: USER, ADMIN).
ALTER TABLE users
    ADD CONSTRAINT users_role_check CHECK (role IN ('USER', 'ADMIN'));
