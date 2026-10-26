-- docker-compose demo: send the demo merchant's webhooks to the merchant-receiver container.
UPDATE merchant
   SET webhook_url = 'http://merchant-receiver:9090/webhooks'
 WHERE id = '11111111-1111-1111-1111-111111111111';
