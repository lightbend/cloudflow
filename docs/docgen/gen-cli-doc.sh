#!/bin/bash -e

#set -x

# Note that the generated text outputs "{cli-plugin}" as the command prefix, not
# "kubectl". In the Asciidoc generation, {cli-plugin} is replaced by "kubectl" or
# "oc plugin", depending on the target K8s platform, "generic" K8s vs. OpenShift.

ROOT_DIR=$(cd "$(dirname "$0")"; pwd)
PROJECT_DIR=$(cd "${ROOT_DIR}/.."; pwd)
MARKDOWN_SRC="${ROOT_DIR}/output/markdown/"
ASCIIDOC_DIR="${PROJECT_DIR}/user-guide/src/main/asciidoc/cli-reference/"
MD2ASCIIDOC_DIR="${ROOT_DIR}/md2asciidoc"

if [ ! -f  "${MD2ASCIIDOC_DIR}"/target/scala-*/md2asciidoc-assembly*.jar ]
then
  echo "=== Generating md2asciidoc tool"
  cd "${MD2ASCIIDOC_DIR}"
  sbt --error assembly
  cd - &>/dev/null
fi
MD2ASCIIDOC_JAR=$(ls "${MD2ASCIIDOC_DIR}"/target/scala-*/md2asciidoc-assembly*.jar)
MD2ASCIIDOC_EXE=(java -jar "${MD2ASCIIDOC_JAR}")

# Generate the .md files
echo "=== Generating .md from kubectl-cloudflow"
kubectl cloudflow documentation generate -f md -p "${MARKDOWN_SRC}"

rm -rf "${ASCIIDOC_DIR}/cloudflow*"
mkdir -p "${ASCIIDOC_DIR}"

# $1 - file to translate
function command_transformation() {
  file="$1"

  # sed commands:
  # /SEE ALSO/,$ d                                - remove everything after the SEE ALSO header, including the header
  # s%^=== \(.*\)$%*\1*%                          - transform the level 3 headers into bold text
  # s/^== cloudflow \(.*\)$/=== The `\1` command/ - transform the level 2 headers into level 3 headers
  # 's/–/--/g' | \                                - replace the long dashes introduced by md2asciidoc by the expected double dashes
  # 's/kubectl/{cli-plugin}/g'                    - replace instances of 'kubectl' with '{cli-plugin}'
  # /^----/,/^----/{s%\[flags]%[parameters]%;N;N;s/\n\n----/\n----/;P;D}
  #                                               - between 2 consecutives '----',
  #                                                 replace 'flags' by 'parameters',
  #                                                 and remove added empty lines at the end of the block
  #
  # awk commands:
  # BEGIN { P=1 }                                 - initial marker to detect code blocks
  # /----/ {if (P) {print "[source,bash,subs=\"attributes\"]"; P=0} else {P=1}
  #                                               - toggle code block marker on '----'
  #                                                 and add code block formatting attributes before starting blocks
  # /^cloudflow/ { $1 = "{cli-plugin} cloudflow"} - if the line starts with 'pipeline', replace it with '{cli-plugin} cloudflow'
  # /^kubectl/ { $1 = "{cli-plugin}"}             - if the line starts with 'kubectl', replace it with '{cli-plugin}'
  # P && (/^{cli-plugin}/ || /^cat/) { print "[source,bash,subs=\"attributes\"]"; print "----"; print $0; print "----"; next}
  #                                               - if a line outside of a code block starts with '{cli-plugin}' or 'cat', wrap it in a code block,
  #                                                 and stop processing the current line
  # {print $0}                                    - print the (updated) content of the line

  # s%^cloudflow%{cli-plugin} cloudflow%;
  "${MD2ASCIIDOC_EXE[@]}" "${MARKDOWN_SRC}/$file" | \
    sed -e '/SEE ALSO/,$ d' \
        -e 's%^=== \(.*\)$%*\1*%' \
        -e 's/^== cloudflow \(.*\)$/=== The `\1` command/' \
        -e 's/kubectl/{cli-plugin}/g' \
        -e 's/–/--/g' | \
    sed '/^----/,/^----/{s%\[flags]%[parameters]%;N;N;s/\n\n----/\n----/;P;D}' | \
    awk ' \
    BEGIN { P=1 } \
    /----/ {if (P) {print "[source,bash,subs=\"attributes\"]"; P=0} else {P=1}} \
    /^cloudflow/ { $1 = "{cli-plugin} cloudflow"} \
    /^kubectl/ { $1 = "{cli-plugin}"} \
    P && (/^{cli-plugin}/ || /^cat/) { print "[source,bash,subs=\"attributes\"]"; print "----"; print $0; print "----"; next} \
    {print $0}' > "${ASCIIDOC_DIR}/${file%md}adoc"

}

# Convert the .md files to .adoc, massaging the result.
echo "=== Converting and tweaking .md into .adoc"
cd "${MARKDOWN_SRC}"
for f in $(find . -type f)
do
  echo $f

  case "$f" in
    *cloudflow.md)
      # Add generic sysnopsis for the command
      cat > "${ASCIIDOC_DIR}/cloudflow.adoc" << EOF
*Synopsis*

[source,bash,subs="attributes"]
----
{cli-plugin} cloudflow [command] [flags]
----

EOF

      # /.*Options/,$!d - remove everything before the Option header
      # /SEE ALSO/,$ d - remove everything after the SEE ALSO header, including the header
      # s%^=== \(.*\)$%*\1*% - transform the level 3 headers into bold text
      "${MD2ASCIIDOC_EXE[@]}" "${MARKDOWN_SRC}/$f" | \
        sed '/.*Options/,$!d;/SEE ALSO/,$ d;s%^=== \(.*\)$%*\1*%' | \
        awk ' \
        BEGIN { P=1 } \
        /----/ {if (P) {print "[source,bash,subs=\"attributes\"]"; P=0} else {P=1}} \
        {print $0}' >> "${ASCIIDOC_DIR}/cloudflow.adoc"
      ;;
    *oc-plugin*)
      command_transformation "$f"
      # add anchor tag at the beginning of the file
      sed -i '1i[[install-oc-plugin]]' "${ASCIIDOC_DIR}/${f%md}adoc"
      ;;
    *)
      command_transformation "$f"
      ;;
  esac
done

echo "=== The updated CLI documentation files at '$ASCIIDOC_DIR'"
