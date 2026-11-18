@echo off
"C:\Users\华为\kafka\kafka_2.13-4.4.0\bin\windows\kafka-console-consumer.bat" --bootstrap-server 127.0.0.1:9092 --topic wms-stock-event --from-beginning --property print.key=true --property key.separator=| --max-messages 30 --timeout-ms 8000
