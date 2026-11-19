-- Migration V6: User Roles and Administrative Authorization Support

-- 1. Add role column to users table with default 'USER'
ALTER TABLE users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER';

-- 2. Constrain permitted roles to USER and ADMIN
ALTER TABLE users ADD CONSTRAINT chk_users_role CHECK (role IN ('USER', 'ADMIN'));

-- 3. Create index for role-based queries
CREATE INDEX idx_users_role ON users(role);
