package util

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"unicode"

	aurora "github.com/logrusorgru/aurora"
)

// PrintError prints a string and prefix it with a red `[Error]` marker
func PrintError(format string, args ...interface{}) {
	str := fmt.Sprintf(format, args...)
	fmt.Printf("%s %s\n", aurora.Bold(aurora.Red("[Error]")), str)
}

// PrintSuccess prints a string and prefix it with a green `[Done]` marker
func PrintSuccess(format string, args ...interface{}) {
	str := fmt.Sprintf(format, args...)
	fmt.Printf("%s %s\n", aurora.Bold(aurora.Green("[Done]")), str)
}

// LogAndExit prints a line and exit
func LogAndExit(format string, args ...interface{}) {
	fmt.Println("")
	PrintError(format, args...)
	os.Exit(1)
}

// LogErrorAndExit prints an error and exits
func LogErrorAndExit(err error) {
	fmt.Println("")
	PrintError("%s", err.Error())
	os.Exit(1)
}

func validateConfigParameterFormat(value string) ([]string, error) {
	split := make([]string, 0)
	for i, r := range value {
		if !unicode.IsDigit(r) {
			first := strings.TrimSpace(string(value[:i]))
			second := strings.TrimSpace(string(value[i:]))
			if len(first) != 0 {
				split = append(split, first)
			}
			if len(second) != 0 {
				split = append(split, second)
			}
			break
		}
	}
	if len(split) != 2 {
		return split, fmt.Errorf("The string '%s' is not a valid", value)
	}

	return split, nil
}

func validateConfigParameterUnits(unit string, validUnits []string) error {

	for _, v := range validUnits {
		if v == unit {
			return nil
		}
	}

	return fmt.Errorf("Unit '%s' is not recognized", unit)
}

// ValidateDuration validates a Typesafe config duration
func ValidateDuration(value string) error {

	// NOTE ! Duration defaults to `ms` if there is no unit attached to the value
	// Check here if the string lacks unit, in that case append `ms` and continue
	// validation after that.
	if i, convErr := strconv.Atoi(value); convErr == nil {
		value = fmt.Sprintf("%d ms", i)
	}

	split, err := validateConfigParameterFormat(value)
	if err != nil {
		return fmt.Errorf("Value `%s` is not a valid duration.", value)
	}

	units := []string{
		"ns", "nano", "nanos", "nanosecond", "nanoseconds",
		"us", "micro", "micros", "microsecond", "microseconds",
		"ms", "milli", "millis", "millisecond", "milliseconds",
		"s", "second", "seconds",
		"m", "minute", "minutes",
		"h", "hour", "hours",
		"d", "day", "days",
	}

	uniterr := validateConfigParameterUnits(split[1], units)
	if uniterr != nil {
		return uniterr
	}
	return nil
}

// ValidateMemorySize validates Typesafe config notation of memory size
func ValidateMemorySize(value string) error {

	split, err := validateConfigParameterFormat(value)
	if err != nil {
		return fmt.Errorf("Value `%s` is not a valid memory size.", value)
	}

	units := []string{
		"B", "b", "byte", "bytes",
		"kB", "kilobyte", "kilobytes",
		"MB", "megabyte", "megabytes",
		"GB", "gigabyte", "gigabytes",
		"TB", "terabyte", "terabytes",
		"PB", "petabyte", "petabytes",
		"EB", "exabyte", "exabytes",
		"ZB", "zettabyte", "zettabytes",
		"YB", "yottabyte", "yottabytes",
		"K", "k", "Ki", "KiB", "kibibyte", "kibibytes",
		"M", "m", "Mi", "MiB", "mebibyte", "mebibytes",
		"G", "g", "Gi", "GiB", "gibibyte", "gibibytes",
		"T", "t", "Ti", "TiB", "tebibyte", "tebibytes",
		"P", "p", "Pi", "PiB", "pebibyte", "pebibytes",
		"E", "e", "Ei", "EiB", "exbibyte", "exbibytes",
		"Z", "z", "Zi", "ZiB", "zebibyte", "zebibytes",
		"Y", "y", "Yi", "YiB", "yobibyte", "yobibytes",
	}

	uniterr := validateConfigParameterUnits(split[1], units)
	if uniterr != nil {
		return uniterr
	}
	return nil
}
