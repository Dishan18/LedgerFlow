-- Local demo: send the demo merchant's webhooks to a receiver on the host (see README).
UPDATE merchant
   SET webhook_url = 'http://localhost:9090/webhooks'
 WHERE id = '11111111-1111-1111-1111-111111111111';
