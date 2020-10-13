for str in $(cat nycTaxiRides-small.json)
do
  echo "Using $str"
  curl -i -X POST taxi-ride-fare.apps.purplehat.lightbend.com/taxi-ride -H "Content-Type: application/json" --data "$str"
done

for str in $(cat nycTaxiFares-small.json)
do
  echo "Using $str"
  curl -i -X POST taxi-ride-fare.apps.purplehat.lightbend.com/taxi-fare -H "Content-Type: application/json" --data "$str"
done
