package verify

import (
	"github.com/stretchr/testify/assert"
	"testing"
)

func Test_parseClassName(t *testing.T) {
	assert.Equal(t, CheckFullPatternMatch("$0000", ClassNamePattern), true)
	assert.Equal(t, CheckFullPatternMatch("0000", ClassNamePattern), false)
	assert.Equal(t, CheckFullPatternMatch("$0000.erere_$", ClassNamePattern), true)
	assert.Equal(t, CheckFullPatternMatch("__2rererere.$", ClassNamePattern), true)
	assert.Equal(t, CheckFullPatternMatch("@@@@@@$$$$__", ClassNamePattern), false)
}

func Test_parseLabelName(t *testing.T) {
	assert.Equal(t, IsDnsLabelCompatible("$0000" ), false)
	assert.Equal(t, IsDnsLabelCompatible("0000www"), true)
	assert.Equal(t, IsDnsLabelCompatible("rfr@3"), false)
	assert.Equal(t, IsDnsLabelCompatible("__2rererere."), false)
	assert.Equal(t, IsDnsLabelCompatible("2212dwdwee__"), false)
}

func Test_parseConfigurationParameter(t *testing.T) {
	assert.Equal(t, CheckFullPatternMatch("0000", ConfigParameterKeyPattern), false)
	assert.Equal(t, CheckFullPatternMatch("$0000", ConfigParameterKeyPattern), false)
	assert.Equal(t, CheckFullPatternMatch("ER12qw", ConfigParameterKeyPattern), true)
	assert.Equal(t, CheckFullPatternMatch("2weee", ConfigParameterKeyPattern), false)
	assert.Equal(t, CheckFullPatternMatch("configuration.", ConfigParameterKeyPattern), true)
}