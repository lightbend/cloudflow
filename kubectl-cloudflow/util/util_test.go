package util

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func Test_ValidationOfDuration(t *testing.T) {

	assert.NoError(t, ValidateDuration("300ms"))
	assert.NoError(t, ValidateDuration("300 ns"))
	assert.NoError(t, ValidateDuration("300 nano"))
	assert.NoError(t, ValidateDuration("300 nanos"))
	assert.NoError(t, ValidateDuration("300 nanosecond"))
	assert.NoError(t, ValidateDuration("300 nanoseconds"))
	assert.NoError(t, ValidateDuration("300 us"))
	assert.NoError(t, ValidateDuration("300 micro"))
	assert.NoError(t, ValidateDuration("300 micros"))
	assert.NoError(t, ValidateDuration("300 microsecond"))
	assert.NoError(t, ValidateDuration("300 microseconds"))
	assert.NoError(t, ValidateDuration("300 ms"))
	assert.NoError(t, ValidateDuration("300 milli"))
	assert.NoError(t, ValidateDuration("300 millis"))
	assert.NoError(t, ValidateDuration("300 millisecond"))
	assert.NoError(t, ValidateDuration("300 milliseconds"))
	assert.NoError(t, ValidateDuration("300 s"))
	assert.NoError(t, ValidateDuration("300 second"))
	assert.NoError(t, ValidateDuration("300 seconds"))
	assert.NoError(t, ValidateDuration("300 m"))
	assert.NoError(t, ValidateDuration("300 minute"))
	assert.NoError(t, ValidateDuration("300 minutes"))
	assert.NoError(t, ValidateDuration("300 h"))
	assert.NoError(t, ValidateDuration("300 hour"))
	assert.NoError(t, ValidateDuration("300 hours"))
	assert.NoError(t, ValidateDuration("300 d"))
	assert.NoError(t, ValidateDuration("300 day"))
	assert.NoError(t, ValidateDuration("300 days"))

	assert.Error(t, ValidateDuration("300 parsec"))

	assert.Error(t, ValidateDuration(" seconds"))
	assert.Error(t, ValidateDuration(" "))
	assert.Error(t, ValidateDuration("100 seconds 200"))

	assert.NoError(t, ValidateDuration("100"))

	assert.Error(t, ValidateDuration("e100"))
}

func Test_ValidationOfMemorySize(t *testing.T) {

	assert.NoError(t, ValidateMemorySize("300 B"))
	assert.NoError(t, ValidateMemorySize("300B"))
	assert.NoError(t, ValidateMemorySize("300 b"))
	assert.NoError(t, ValidateMemorySize("300 byte"))
	assert.NoError(t, ValidateMemorySize("300byte"))
	assert.NoError(t, ValidateMemorySize("300 bytes"))
	assert.NoError(t, ValidateMemorySize("300 kB"))
	assert.NoError(t, ValidateMemorySize("300 kilobyte"))
	assert.NoError(t, ValidateMemorySize("300 kilobytes"))
	assert.NoError(t, ValidateMemorySize("300 MB"))
	assert.NoError(t, ValidateMemorySize("300 megabyte"))
	assert.NoError(t, ValidateMemorySize("300 megabytes"))
	assert.NoError(t, ValidateMemorySize("300 GB"))
	assert.NoError(t, ValidateMemorySize("300 gigabyte"))
	assert.NoError(t, ValidateMemorySize("300 gigabytes"))
	assert.NoError(t, ValidateMemorySize("300 TB"))
	assert.NoError(t, ValidateMemorySize("300 terabyte"))
	assert.NoError(t, ValidateMemorySize("300 terabytes"))
	assert.NoError(t, ValidateMemorySize("300 PB"))
	assert.NoError(t, ValidateMemorySize("300 petabyte"))
	assert.NoError(t, ValidateMemorySize("300 petabytes"))
	assert.NoError(t, ValidateMemorySize("300 EB"))
	assert.NoError(t, ValidateMemorySize("300 exabyte"))
	assert.NoError(t, ValidateMemorySize("300 exabytes"))
	assert.NoError(t, ValidateMemorySize("300 ZB"))
	assert.NoError(t, ValidateMemorySize("300 zettabyte"))
	assert.NoError(t, ValidateMemorySize("300 zettabytes"))
	assert.NoError(t, ValidateMemorySize("300 YB"))
	assert.NoError(t, ValidateMemorySize("300 yottabyte"))
	assert.NoError(t, ValidateMemorySize("300 yottabytes"))
	assert.NoError(t, ValidateMemorySize("300 K"))
	assert.NoError(t, ValidateMemorySize("300 k"))
	assert.NoError(t, ValidateMemorySize("300 Ki"))
	assert.NoError(t, ValidateMemorySize("300 KiB"))
	assert.NoError(t, ValidateMemorySize("300 kibibyte"))
	assert.NoError(t, ValidateMemorySize("300 kibibytes"))
	assert.NoError(t, ValidateMemorySize("300 M"))
	assert.NoError(t, ValidateMemorySize("300 m"))
	assert.NoError(t, ValidateMemorySize("300 Mi"))
	assert.NoError(t, ValidateMemorySize("300 MiB"))
	assert.NoError(t, ValidateMemorySize("300 mebibyte"))
	assert.NoError(t, ValidateMemorySize("300 mebibytes"))
	assert.NoError(t, ValidateMemorySize("300 G"))
	assert.NoError(t, ValidateMemorySize("300 g"))
	assert.NoError(t, ValidateMemorySize("300 Gi"))
	assert.NoError(t, ValidateMemorySize("300 GiB"))
	assert.NoError(t, ValidateMemorySize("300 gibibyte"))
	assert.NoError(t, ValidateMemorySize("300 gibibytes"))
	assert.NoError(t, ValidateMemorySize("300 T"))
	assert.NoError(t, ValidateMemorySize("300 t"))
	assert.NoError(t, ValidateMemorySize("300 Ti"))
	assert.NoError(t, ValidateMemorySize("300 TiB"))
	assert.NoError(t, ValidateMemorySize("300 tebibyte"))
	assert.NoError(t, ValidateMemorySize("300 tebibytes"))
	assert.NoError(t, ValidateMemorySize("300 P"))
	assert.NoError(t, ValidateMemorySize("300 p"))
	assert.NoError(t, ValidateMemorySize("300 Pi"))
	assert.NoError(t, ValidateMemorySize("300 PiB"))
	assert.NoError(t, ValidateMemorySize("300 pebibyte"))
	assert.NoError(t, ValidateMemorySize("300 pebibytes"))
	assert.NoError(t, ValidateMemorySize("300 E"))
	assert.NoError(t, ValidateMemorySize("300 e"))
	assert.NoError(t, ValidateMemorySize("300 Ei"))
	assert.NoError(t, ValidateMemorySize("300 EiB"))
	assert.NoError(t, ValidateMemorySize("300 exbibyte"))
	assert.NoError(t, ValidateMemorySize("300 exbibytes"))
	assert.NoError(t, ValidateMemorySize("300 Z"))
	assert.NoError(t, ValidateMemorySize("300 z"))
	assert.NoError(t, ValidateMemorySize("300 Zi"))
	assert.NoError(t, ValidateMemorySize("300 ZiB"))
	assert.NoError(t, ValidateMemorySize("300 zebibyte"))
	assert.NoError(t, ValidateMemorySize("300 zebibytes"))
	assert.NoError(t, ValidateMemorySize("300 Y"))
	assert.NoError(t, ValidateMemorySize("300 y"))
	assert.NoError(t, ValidateMemorySize("300 Yi"))
	assert.NoError(t, ValidateMemorySize("300 YiB"))
	assert.NoError(t, ValidateMemorySize("300 yobibyte"))
	assert.NoError(t, ValidateMemorySize("300 yobibytes"))

	assert.Error(t, ValidateMemorySize("300 parsec"))

	assert.Error(t, ValidateMemorySize(" exbi"))
	assert.Error(t, ValidateMemorySize(" "))
	assert.Error(t, ValidateMemorySize("100 exbi 200"))
}

func Test_DiffWithDuplicates(t *testing.T) {
	a := []string{"1", "2", "2", "3", "4"}
	b :=[]string{"1", "2"}
	ret := Diff(a, b)
	assert.ElementsMatch(t, ret, []string{"2", "3", "4"})
}
func Test_DiffWithBothEmpty(t *testing.T) {
	a := []string{}
	b :=[]string{}
	ret := Diff(a, b)
	assert.ElementsMatch(t, ret, []string{})
}

func Test_DiffWithFirstfEmpty(t *testing.T) {
	a := []string{}
	b :=[]string{"1", "2"}
	ret := Diff(a, b)
	assert.ElementsMatch(t, ret, a)
}

func Test_DiffWithSecondfEmpty(t *testing.T) {
	a := []string{"1", "2", "2", "3", "4"}
	b :=[]string{}
	ret := Diff(a, b)
	assert.ElementsMatch(t, ret, a)
}
