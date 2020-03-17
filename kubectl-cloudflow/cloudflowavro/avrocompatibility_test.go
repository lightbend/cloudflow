package cloudflowavro

import (
	"github.com/hamba/avro"
	"github.com/stretchr/testify/assert"
	"log"
	"testing"
)

func Test_SimpleSchemaCompatibility(t *testing.T) {

	readerSchema := `{
	"type": "long",
	"name": "data"
}`

	writerSchema := `{
       "name": "data", 
       "type": "int"
}`

	reader, err := avro.ParseWithCache(readerSchema, "", avro.DefaultSchemaCache)
	writer, err := avro.ParseWithCache(writerSchema, "", avro.DefaultSchemaCache)
	if err != nil {
		log.Fatal(err)
	}
	s := avro.NewSchemaCompatibility()
	assert.Equal(t, err, nil)
	assert.Equal(t, s.Compatible(reader, writer), nil)
	assert.Equal(t, CheckSchemaCompatibility(readerSchema, writerSchema), nil)
}
func Test_SchemaCompatibilityWithLogicalTypes(t *testing.T) {
	var readerSchema1 = `{
		"type" : "record",
		"name": "test",
		"fields" : [
	{
		"type": {
			"type": "int",
			"logicalType": "time-millis"
		},
		"name": "simple"
	}] }`

	var writerSchema1 = `
	{
	"type" : "record",
	"name": "test",
	"fields" : [
	{
	"name": "simple",
	"type": "int"
	}]}`

	reader1, err := avro.ParseWithCache(readerSchema1, "", avro.DefaultSchemaCache)

	assert.Equal(t, err, nil)

	writer1, err := avro.ParseWithCache(writerSchema1, "", avro.DefaultSchemaCache)

	assert.Equal(t, CheckSchemaCompatibility(readerSchema1, writerSchema1), nil)

	assert.Equal(t, err, nil)

	s := avro.NewSchemaCompatibility()

	assert.Equal(t, s.Compatible(reader1, writer1), nil)

	readerSchema2 := `{
	"type": "record",
	"name": "simple",
	"namespace": "org.hamba.avro",
	"fields": [{
			"name": "a",
			"type": {
				"type": "int",
				"logicalType": "time-millis"
			}
		},
		{
			"name": "b",
			"type": "string"
		}
	]
}`
	writerSchema2 := `{
	"type": "record",
	"name": "simple",
	"namespace": "org.hamba.avro",
	"fields": [{
			"name": "a",

			"type": "int",
			"logicalType": "time-millis"

		},
		{
			"name": "b",
			"type": "string"
		}
	]
}`
	reader2, err := avro.ParseWithCache(readerSchema2, "", avro.DefaultSchemaCache)
	assert.Equal(t, err, nil)
	writer2, err := avro.ParseWithCache(writerSchema2, "", avro.DefaultSchemaCache)
	assert.Equal(t, err, nil)
	assert.Equal(t, s.Compatible(reader2, writer2), nil)
	assert.Equal(t, CheckSchemaCompatibility(readerSchema2, writerSchema2), nil)
}
