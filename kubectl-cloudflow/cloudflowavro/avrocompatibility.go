package cloudflowavro

import (
	"github.com/hamba/avro"
	"github.com/linkedin/goavro/v2"
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

type IncompatibleCanonicalError struct {
	msg string
}

func (e *ReaderSchemaValidationError) Error() string { return e.msg }

func (e *WriterSchemaValidationError) Error() string { return e.msg }

func (e *IncompatibleCanonicalError) Error() string { return e.msg }

func CheckSchemaCompatibility(reader string, writer string) error {
    _, err := ValidateSchema(reader)

	if err != nil {
		return &ReaderSchemaValidationError{msg: err.Error()}
	}

	_, err = ValidateSchema(writer)

	if err != nil {
		return &WriterSchemaValidationError{msg: err.Error()}
	}

	readerSchema, err := avro.ParseWithCache(reader, "", avro.DefaultSchemaCache)
	writerSchema, err := avro.ParseWithCache(writer, "", avro.DefaultSchemaCache)
	s := avro.NewSchemaCompatibility()

	return s.Compatible(readerSchema, writerSchema)
}

