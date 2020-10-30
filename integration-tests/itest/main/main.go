package main 

import (
	"fmt"
	"github.com/lightbend/cloudflow/integration-test/itest/k8s"


)

func main(){
	out, err := k8s.CreateNamespace("swiss-knife")
	fmt.Printf("out: %s", out)
	fmt.Printf("err: %s", err)
}