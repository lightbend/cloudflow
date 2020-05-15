package printutil

import (
	"fmt"
	"os"

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
