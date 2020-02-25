for str in $(cat test-data/10-storm.json)
do
  echo "Using $str"
  curl -i -X POST sensor-data-scala.apps.purplehat.lightbend.com/sensor-data -H "Content-Type: application/json" --data "$str"
done
