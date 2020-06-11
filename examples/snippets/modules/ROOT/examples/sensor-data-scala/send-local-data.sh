for json in $(cat test-data/one-record-per-line.json)
do
  echo "Sending $json"
  curl -i -X POST localhost:3000 -H "Content-Type: application/json" --data "$json"
done
