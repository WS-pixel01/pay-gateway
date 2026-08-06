-- ===================== Channel configuration seed =====================
-- Notes: ALIPAY signs with RSA2; ChannelKeyInitializer generates the key pair on startup and writes it back.
--        WECHAT signs with MD5 and encrypts the payload with AES-GCM (mirroring WeChat v3 "resource" encryption).
--        BALANCE is the internal balance channel and needs no signature at all.
INSERT INTO pay_channel_config
(channel_type, channel_name, merchant_id, app_id, api_key, aes_key, sign_type, gateway_url, notify_url,
 enabled, priority, min_amount, max_amount, support_regions, fee_rate)
VALUES ('ALIPAY', 'Alipay Sandbox', '2088102147948060', '9021000135612345', NULL, NULL, 'RSA2',
        'https://openapi-sandbox.dl.alipaydev.com/gateway.do', '/api/webhooks/alipay',
        1, 10, 0.01, 500000.00, 'ALL', 0.006000);

INSERT INTO pay_channel_config
(channel_type, channel_name, merchant_id, app_id, api_key, aes_key, sign_type, gateway_url, notify_url,
 enabled, priority, min_amount, max_amount, support_regions, fee_rate)
VALUES ('WECHAT', 'WeChat Pay Sandbox', '1900000109', 'wxd930ea5d5a258f4f',
        'sandbox_key_192006250b4c09247ec02edce69f6a2d',
        'MTIzNDU2Nzg5MGFiY2RlZjEyMzQ1Njc4OTBhYmNkZWY=', 'MD5',
        'https://api.mch.weixin.qq.com/sandboxnew/pay/unifiedorder', '/api/webhooks/wechat',
        1, 20, 0.01, 50000.00, 'CN,HK', 0.006000);

INSERT INTO pay_channel_config
(channel_type, channel_name, merchant_id, app_id, api_key, aes_key, sign_type, gateway_url, notify_url,
 enabled, priority, min_amount, max_amount, support_regions, fee_rate)
VALUES ('BALANCE', 'Account Balance', 'INNER', 'INNER', 'inner_balance_key', NULL, 'MD5',
        'inner://balance', '/api/webhooks/balance',
        1, 5, 0.01, 20000.00, 'ALL', 0.000000);

-- ===================== Balance account seed =====================
INSERT INTO user_account (user_id, balance, frozen_amount) VALUES ('U10001', 10000.00, 0.00);
INSERT INTO user_account (user_id, balance, frozen_amount) VALUES ('U10002', 500.00, 0.00);
INSERT INTO user_account (user_id, balance, frozen_amount) VALUES ('U10003', 0.50, 0.00);
