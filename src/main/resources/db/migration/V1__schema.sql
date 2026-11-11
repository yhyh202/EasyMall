CREATE TABLE mall_user (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 username VARCHAR(50) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL,
 role VARCHAR(20) NOT NULL DEFAULT 'USER',
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE auth_token (
 token_hash VARCHAR(64) PRIMARY KEY,
 user_id BIGINT NOT NULL,
 expires_at TIMESTAMP NOT NULL,
 FOREIGN KEY (user_id) REFERENCES mall_user(id)
);
CREATE INDEX idx_token_expiry ON auth_token(expires_at);
CREATE TABLE category (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(80) NOT NULL UNIQUE
);
CREATE TABLE product (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 category_id BIGINT NOT NULL,
 name VARCHAR(150) NOT NULL,
 description VARCHAR(2000) NOT NULL DEFAULT '',
 price DECIMAL(12,2) NOT NULL,
 stock INT NOT NULL DEFAULT 0,
 active BOOLEAN NOT NULL DEFAULT TRUE,
 FOREIGN KEY (category_id) REFERENCES category(id),
 CHECK (price > 0), CHECK (stock >= 0)
);
CREATE INDEX idx_product_category ON product(category_id, active, id);
CREATE TABLE address (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 recipient VARCHAR(80) NOT NULL,
 phone VARCHAR(30) NOT NULL,
 detail VARCHAR(500) NOT NULL,
 is_default BOOLEAN NOT NULL DEFAULT FALSE,
 FOREIGN KEY (user_id) REFERENCES mall_user(id)
);
CREATE INDEX idx_address_user ON address(user_id);
CREATE TABLE cart_item (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 product_id BIGINT NOT NULL,
 quantity INT NOT NULL,
 UNIQUE (user_id, product_id),
 FOREIGN KEY (user_id) REFERENCES mall_user(id),
 FOREIGN KEY (product_id) REFERENCES product(id),
 CHECK (quantity BETWEEN 1 AND 999)
);
CREATE TABLE mall_order (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 request_key VARCHAR(64) NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 status VARCHAR(20) NOT NULL,
 total_amount DECIMAL(14,2) NOT NULL,
 recipient VARCHAR(80) NOT NULL,
 phone VARCHAR(30) NOT NULL,
 address_detail VARCHAR(500) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 paid_at TIMESTAMP NULL,
 shipping_no VARCHAR(100) NULL,
 UNIQUE (user_id, request_key),
 FOREIGN KEY (user_id) REFERENCES mall_user(id)
);
CREATE INDEX idx_order_user ON mall_order(user_id, id);
CREATE INDEX idx_order_status ON mall_order(status, id);
CREATE TABLE order_item (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 order_id BIGINT NOT NULL,
 product_id BIGINT NOT NULL,
 product_name VARCHAR(150) NOT NULL,
 unit_price DECIMAL(12,2) NOT NULL,
 quantity INT NOT NULL,
 FOREIGN KEY (order_id) REFERENCES mall_order(id),
 FOREIGN KEY (product_id) REFERENCES product(id)
);
CREATE INDEX idx_order_item_order ON order_item(order_id);
CREATE TABLE payment (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 order_id BIGINT NOT NULL UNIQUE,
 user_id BIGINT NOT NULL,
 request_key VARCHAR(64) NOT NULL,
 amount DECIMAL(14,2) NOT NULL,
 status VARCHAR(20) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE (user_id, request_key),
 FOREIGN KEY (order_id) REFERENCES mall_order(id),
 FOREIGN KEY (user_id) REFERENCES mall_user(id)
);
CREATE TABLE stock_log (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 product_id BIGINT NOT NULL,
 order_id BIGINT NULL,
 actor_id BIGINT NOT NULL,
 delta INT NOT NULL,
 reason VARCHAR(30) NOT NULL,
 request_key VARCHAR(64) NULL,
 UNIQUE (actor_id, request_key),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (product_id) REFERENCES product(id),
 FOREIGN KEY (order_id) REFERENCES mall_order(id),
 FOREIGN KEY (actor_id) REFERENCES mall_user(id)
);
CREATE INDEX idx_stock_product ON stock_log(product_id, id);
