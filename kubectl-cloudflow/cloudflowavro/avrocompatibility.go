package cloudflowavro

import (
	"errors"
	"fmt"
	"github.com/hamba/avro"
	jsoniter "github.com/json-iterator/go"
	"github.com/linkedin/goavro/v2"
	"strings"
)
const (
	DECIMAL = "decimal"
	UUID = "uuid"
	DATE = "date"
	TIME_MILLIS = "time-millis"
	TIME_MICROS = "time-micros"
	TIMESTAMP_MILLIS = "timestamp-millis"
	TIMESTAMP_MICROS = "timestamp-micros"
)

func ValidateSchema(schema string) (*string, error) {
	codec, err := goavro.NewCodec(schema)
	if err != nil {
		return nil, err
	} else {
		canonical := codec.CanonicalSchema()
		return &canonical, nil
	}
}

type ReaderSchemaValidationError struct {
	msg string
}

type WriterSchemaValidationError struct {
	msg string
}

func (e *ReaderSchemaValidationError) Error() string { return e.msg }

func (e *WriterSchemaValidationError) Error() string { return e.msg }

func CheckSchemaCompatibility(reader string, writer string) error {
	readerCanonical, err := ValidateSchema(reader)

	if err != nil {
		return &ReaderSchemaValidationError{msg: err.Error()}
	}

	writerCanonical, err := ValidateSchema(writer)

	if err != nil {
		return &WriterSchemaValidationError{msg: err.Error()}
	}

	// return quickly if schemas have the same canonical form
	// this is not enough to prove schema compatibility as default
	// values for example are removed (http://apache-avro.679487.n3.nabble.com/Re-Parsing-canonical-forms-with-schemas-having-default-values-td4037685.html).
	if *readerCanonical != *writerCanonical {
		return nil
	}

	readerSchema, err := ParseValidatedSchemaWithCache(reader, "", avro.DefaultSchemaCache)
	writerSchema, err := ParseValidatedSchemaWithCache(writer, "", avro.DefaultSchemaCache)
	s := avro.NewSchemaCompatibility()

	return s.Compatible(readerSchema, writerSchema)
}

// This code part is copied and modified from https://github.com/hamba/avro
// TODO: remove this when https://github.com/hamba/avro/issues/29 is done

// ParseValidatedSchemaWithCache parses a schema string using the given namespace and  schema cache.
func ParseValidatedSchemaWithCache(schema, namespace string, cache *avro.SchemaCache) (avro.Schema, error) {
	var json interface{}
	if err := jsoniter.Unmarshal([]byte(schema), &json); err != nil {
		json = schema
	}

	return parseType(namespace, json, cache)
}

func parseType(namespace string, v interface{}, cache *avro.SchemaCache) (avro.Schema, error) {
	switch val := v.(type) {
	case nil:
		return &avro.NullSchema{}, nil

	case string:
		return parsePrimitive(namespace, val, cache)

	case map[string]interface{}:
		return parseComplex(namespace, val, cache)

	case []interface{}:
		return parseUnion(namespace, val, cache)
	}

	return nil, fmt.Errorf("avro: unknown type: %v", v)
}

func parsePrimitive(namespace, s string, cache *avro.SchemaCache) (avro.Schema, error) {
	typ := avro.Type(s)
	switch typ {
	case avro.Null:
		return &avro.NullSchema{}, nil

	case avro.String, avro.Bytes, avro.Int, avro.Long, avro.Float, avro.Double, avro.Boolean:
		return avro.NewPrimitiveSchema(typ), nil

	default:
		schema := cache.Get(fullName(namespace, s))
		if schema != nil {
			return schema, nil
		}

		return nil, fmt.Errorf("avro: unknown type: %s", s)
	}
}

func parseComplex(namespace string, m map[string]interface{}, cache *avro.SchemaCache) (avro.Schema, error) {
	if val, ok := m["type"].([]interface{}); ok {
		return parseUnion(namespace, val, cache)
	}
	isLogical := false
	var logicalTypeName *string = nil
	var mNew = m
	// skip Logical type fields, for compatibility checks
	// we only care about the enclosed type field
	switch val := m["type"].(type) {
	  case map[string]interface{}:
	  	value, ok := val["logicalType"]

		 if ok {
		 	mNew["type"] = val["type"]
		 	lValue, _ := value.(string)
		 	logicalTypeName = &lValue
		 	isLogical = true
		 }
	}

	str, ok := mNew["type"].(string)

	if !ok {
		return nil, fmt.Errorf("avro: unknown type: %+v", mNew)
	}
	typ := avro.Type(str)

	if isLogical && logicalTypeName != nil {
		switch *logicalTypeName {
		case DECIMAL:
			if typ == avro.Bytes {
				return avro.NewPrimitiveSchema(typ), nil
			}
			if typ == avro.Fixed {
				return parseFixed(namespace, mNew, cache)
			}
		case UUID:
		case TIMESTAMP_MILLIS:
		case TIMESTAMP_MICROS:
		case TIME_MILLIS:
		case TIME_MICROS:
		case DATE:
			return avro.NewPrimitiveSchema(typ), nil
		default:
			return avro.NewPrimitiveSchema(typ), nil
		}
	}

	switch typ {
	case avro.Null:
		return &avro.NullSchema{}, nil

	case avro.String, avro.Bytes, avro.Int, avro.Long, avro.Float, avro.Double, avro.Boolean:
		return avro.NewPrimitiveSchema(typ), nil

	case avro.Record, avro.Error:
		return parseRecord(typ, namespace, mNew, cache)

	case avro.Enum:
		return parseEnum(namespace, mNew, cache)

	case avro.Array:
		return parseArray(namespace, mNew, cache)

	case avro.Map:
		return parseMap(namespace, mNew, cache)

	case avro.Fixed:
		return parseFixed(namespace, mNew, cache)

	default:
		return parseType(namespace, string(typ), cache)
	}
}

func parseRecord(typ avro.Type, namespace string, m map[string]interface{}, cache *avro.SchemaCache) (avro.Schema, error) {
	name, newNamespace, err := resolveFullName(m)
	if err != nil {
		return nil, err
	}
	if newNamespace != "" {
		namespace = newNamespace
	}

	fs, ok := m["fields"].([]interface{})
	if !ok {
		return nil, errors.New("avro: record must have an array of fields")
	}
	fields := make([]*avro.Field, len(fs))

	var rec *avro.RecordSchema
	switch typ {
	case avro.Record:
		rec, err = avro.NewRecordSchema(name, namespace, fields)

	case avro.Error:
		rec, err = avro.NewErrorRecordSchema(name, namespace, fields)
	}
	if err != nil {
		return nil, err
	}

	cache.Add(rec.FullName(), avro.NewRefSchema(rec))

	for k, v := range m {
		rec.AddProp(k, v)
	}

	for i, f := range fs {
		field, err := parseField(namespace, f, cache)
		if err != nil {
			return nil, err
		}

		fields[i] = field
	}

	return rec, nil
}

func parseField(namespace string, v interface{}, cache *avro.SchemaCache) (*avro.Field, error) {
	m, ok := v.(map[string]interface{})
	if !ok {
		return nil, fmt.Errorf("avro: invalid field: %+v", v)
	}

	name, err := resolveName(m)
	if err != nil {
		return nil, err
	}

	if _, ok := m["type"]; !ok {
		return nil, errors.New("avro: field requires a type")
	}

	typ, err := parseType(namespace, m["type"], cache)
	if err != nil {
		return nil, err
	}

	field, err := avro.NewField(name, typ, m["default"])
	if err != nil {
		return nil, err
	}

	for k, v := range m {
		field.AddProp(k, v)
	}

	return field, nil
}

func parseEnum(namespace string, m map[string]interface{}, cache *avro.SchemaCache) (avro.Schema, error) {
	name, newNamespace, err := resolveFullName(m)
	if err != nil {
		return nil, err
	}
	if newNamespace != "" {
		namespace = newNamespace
	}

	syms, ok := m["symbols"].([]interface{})
	if !ok {
		return nil, errors.New("avro: enum must have a non-empty array of symbols")
	}

	symbols := make([]string, len(syms))
	for i, sym := range syms {
		str, ok := sym.(string)
		if !ok {
			return nil, fmt.Errorf("avro: invalid symbol: %+v", sym)
		}

		symbols[i] = str
	}

	enum, err := avro.NewEnumSchema(name, namespace, symbols)
	if err != nil {
		return nil, err
	}

	cache.Add(enum.FullName(), enum)

	for k, v := range m {
		enum.AddProp(k, v)
	}

	return enum, nil
}

func parseArray(namespace string, m map[string]interface{}, cache *avro.SchemaCache) (avro.Schema, error) {
	items, ok := m["items"]
	if !ok {
		return nil, errors.New("avro: array must have an items key")
	}

	schema, err := parseType(namespace, items, cache)
	if err != nil {
		return nil, err
	}

	arr := avro.NewArraySchema(schema)

	for k, v := range m {
		arr.AddProp(k, v)
	}

	return arr, nil
}

func parseMap(namespace string, m map[string]interface{}, cache *avro.SchemaCache) (avro.Schema, error) {
	values, ok := m["values"]
	if !ok {
		return nil, errors.New("avro: map must have an values key")
	}

	schema, err := parseType(namespace, values, cache)
	if err != nil {
		return nil, err
	}

	ms := avro.NewMapSchema(schema)

	for k, v := range m {
		ms.AddProp(k, v)
	}

	return ms, nil
}

func parseUnion(namespace string, v []interface{}, cache *avro.SchemaCache) (avro.Schema, error) {
	var err error
	types := make([]avro.Schema, len(v))
	for i := range v {
		types[i], err = parseType(namespace, v[i], cache)
		if err != nil {
			return nil, err
		}
	}

	return avro.NewUnionSchema(types)
}

func parseFixed(namespace string, m map[string]interface{}, cache *avro.SchemaCache) (avro.Schema, error) {
	name, newNamespace, err := resolveFullName(m)
	if err != nil {
		return nil, err
	}
	if newNamespace != "" {
		namespace = newNamespace
	}

	size, ok := m["size"].(float64)
	if !ok {
		return nil, errors.New("avro: fixed must have a size")
	}

	fixed, err := avro.NewFixedSchema(name, namespace, int(size))
	if err != nil {
		return nil, err
	}

	cache.Add(fixed.FullName(), fixed)

	for k, v := range m {
		fixed.AddProp(k, v)
	}

	return fixed, nil
}

func fullName(namespace, name string) string {
	if len(namespace) == 0 || strings.ContainsRune(name, '.') {
		return name
	}

	return namespace + "." + name
}

func resolveName(m map[string]interface{}) (string, error) {
	name, ok := m["name"].(string)
	if !ok {
		return "", errors.New("avro: name key required")
	}

	return name, nil
}

func resolveFullName(m map[string]interface{}) (string, string, error) {
	name, err := resolveName(m)
	if err != nil {
		return "", "", err
	}

	namespace, ok := m["namespace"].(string)
	if !ok {
		return name, "", nil
	}
	if namespace == "" {
		return "", "", errors.New("avro: namespace key must be non-empty or omitted")
	}

	return name, namespace, nil
}

