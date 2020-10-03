package config

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func Test_validationExists(t *testing.T){
	spec := createSpec()
	labelConfigSection := newConfig(`
	cloudflow.runtimes.spark.kubernetes.pods.pod {
	  volumes {
	    bar {
	      pvc {
	        name = myclaim
	        readOnly = false
	      } 
	    }
	  }
	  containers.container {
	    volume-mounts {
	      bar {
	        mountPath: "/tmp/some"
	        readOnly =  false
	      }
	    }
	  }
	}
	`)
	assert.Empty(t, validateConfig(labelConfigSection, spec))
}