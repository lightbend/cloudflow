FROM antora/antora:2.3.2

RUN yarn global add asciidoc-link-check
RUN yarn global add antora-lunr
RUN yarn global add antora-site-generator-lunr

ENV DOCSEARCH_ENABLED=true
ENV DOCSEARCH_ENGINE=lunr
ENV DOCSEARCH_LANGS=en
ENV DOCSEARCH_INDEX_VERSION=latest

LABEL description="antora/antora image with the additional 'asciidoc-link-check', 'antora-lunr' and 'antora-site-generator-lunr' tool"

# Generate an index file https://github.com/Mogztter/antora-lunr#generate-an-index-file
RUN sed -i "/strict/a const generateIndex = require('antora-lunr')" ${NODE_PATH}/@antora/site-generator-default/lib/generate-site.js
RUN sed -i "/const siteFiles/a siteFiles.push(generateIndex.createIndexFile(generateIndex(playbook, pages, contentCatalog, env)))" ${NODE_PATH}/@antora/site-generator-default/lib/generate-site.js

CMD ["antora", "--generator", "antora-site-generator-lunr"]
