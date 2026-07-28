FROM node:20-bookworm-slim

ENV NODE_ENV=production \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PRESENTATION_SKILL_HOME=/app/vendor/presentation-skill \
    PPT_SKILL_TEMP_ROOT=/tmp/a12-ppt-skill-runner \
    PPT_SKILL_RESULT_ROOT=/app/data/results \
    PPT_SKILL_STYLE_PRESET=forest-research

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        python3 \
        python3-pip \
        python3-venv \
        libreoffice-impress \
        poppler-utils \
        fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt ./requirements.txt
RUN python3 -m venv /opt/venv \
    && /opt/venv/bin/pip install --no-cache-dir -r requirements.txt
ENV PATH="/opt/venv/bin:${PATH}"

COPY package.json package-lock.json ./
RUN npm ci --include=dev

COPY vendor/presentation-skill/package.json vendor/presentation-skill/package-lock.json ./vendor/presentation-skill/
RUN cd vendor/presentation-skill && npm ci --omit=dev

COPY tsconfig.json ./
COPY schema ./schema
COPY fixtures ./fixtures
COPY src ./src
COPY test ./test
COPY vendor/presentation-skill/LICENSE vendor/presentation-skill/UPSTREAM.json ./vendor/presentation-skill/
COPY vendor/presentation-skill/scripts ./vendor/presentation-skill/scripts
COPY vendor/presentation-skill/templates ./vendor/presentation-skill/templates
COPY vendor/presentation-skill/references ./vendor/presentation-skill/references
COPY vendor/ppt-skills-editorial-grid ./vendor/ppt-skills-editorial-grid

RUN npm run build \
    && mkdir -p /app/data/results /tmp/a12-ppt-skill-runner \
    && chown -R node:node /app /tmp/a12-ppt-skill-runner

USER node
EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:8090/health').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"

CMD ["node", "dist/src/server.js"]
