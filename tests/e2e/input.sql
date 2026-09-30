-- 端到端测试输入：覆盖 auto / confirm / blocked 三个档位
CREATE TABLE retail.demo_order (
    order_id     BIGINT NOT NULL DEFAULT nextval('retail.demo_seq'),
    order_status VARCHAR(16) NOT NULL DEFAULT 'NEW',
    payload      JSONB,
    created_at   TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

SELECT order_id, order_status
FROM retail.demo_order
WHERE payload IS NOT NULL
ORDER BY order_id;

CREATE SEQUENCE retail.demo_seq START WITH 10001 INCREMENT BY 1;

UPDATE retail.demo_order
SET order_status = 'PAID'
WHERE order_id = 10001;
