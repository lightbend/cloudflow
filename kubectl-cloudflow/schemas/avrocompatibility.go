package schemas

import (
	hamba "github.com/hamba/avro"
	linkedin "github.com/linkedin/goavro/v2"
)

// checkSchemaIsValid uses LinkedIn's avro go lib
// to verify that a schema is valid.
// If codec is returned then we consider the schema valid.
// Note: This library seems to be widely used and seems consistent enough with the spec,
// thus the choice for schema validation.
func checkSchemaIsValid(schema string) error {
	if _, err := linkedin.NewCodec(schema); err != nil {
		return err
	} else {
		return nil
	}
}

// A custom error to indicate that the reader's schema
// has failed parsing done via the Linkedin avro go lib.
type ReaderSchemaValidationError struct {
	msg string
}

// A custom error to indicate that the writer's schema
// has failed parsing done via the Linkedin avro go lib.
type WriterSchemaValidationError struct {
	msg string
}

func (e *ReaderSchemaValidationError) Error() string { return e.msg }

func (e *WriterSchemaValidationError) Error() string { return e.msg }

// CheckSchemaCompatibility is used for checking compatibility between
// reader's and writer's schema. It uses the hamba/avro library which
// supports compatibility checks, unlike the LinkedIn one.
// Since we cannot use one library here for compatibility
// checks we have to parse the schemas again and implicitly validate them.
// This helps track any inconsistencies between the two libraries.
// TODO: We want to use one library for both validation and compatibility checks in the future.
func CheckSchemaCompatibility(reader string, writer string) error {
	if err := checkSchemaIsValid(reader); err != nil {
		return &ReaderSchemaValidationError{msg: err.Error()}
	}

	if err := checkSchemaIsValid(writer); err != nil{
		return &WriterSchemaValidationError{msg: err.Error()}
	}

	readerSchema, err := hamba.ParseWithCache(reader, "", hamba.DefaultSchemaCache)

	if err != nil {
		return err
	}

	writerSchema, err := hamba.ParseWithCache(writer, "", hamba.DefaultSchemaCache)

	if err != nil {
		return err
	}

	s := hamba.NewSchemaCompatibility()
	return s.Compatible(readerSchema, writerSchema)
}
