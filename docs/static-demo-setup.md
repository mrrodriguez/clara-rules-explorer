# Clara Rules Explorer: Static Demo Setup & Hosting Guide

This guide explains how to build, test, and host a fully interactive static demo of the **Clara Rules Explorer** on GitHub Pages.

---

## 1. How the Static Demo Works

Since the explorer is a visualization tool, visitors don't need to compile new rulebases or facts in real-time. They just need to browse and inspect a representative sample dataset.

1. **Scraped Data**: We run a script to scrape all API endpoints from a locally running Clojure backend and save them as static JSON files in `ui/static/demo-data/`.
2. **Conditional API Routing**: When built in demo mode, the SvelteKit API client redirects requests from `/v1/...` to the static `/demo-data/...json` files.
3. **Single Page Application (SPA)**: SvelteKit is built using `@sveltejs/adapter-static` with a fallback page (`404.html`), allowing client-side dynamic routing to resolve `/rules/[id]` and `/session/facts/[id]` in the browser.

---

## 2. Dynamic Route Resolution (handleUnseenRoutes)

SvelteKit's prerender crawler starts at `/` and follows links. However, it cannot statically discover dynamic parameters like specific rule FQNs or fact IDs during compilation. 

We set `prerender.handleUnseenRoutes: 'ignore'` in `svelte.config.js` to prevent build failures. When a visitor navigates directly to a dynamic route, GitHub Pages serves the fallback `404.html` shell. SvelteKit's router then mounts, reads the URL, and fetches the static data JSON file dynamically.

---

## 3. Step-by-Step Implementation

### Step 1: Install Svelte Static Adapter
Install the static adapter as a devDependency in the `ui` directory:
```bash
cd ui
pnpm install
pnpm add -D @sveltejs/adapter-static
```

### Step 2: Build the Demo Files
Compile the static build files:
```bash
pnpm build:demo
```
This script (configured in `ui/package.json`) runs the build with `VITE_DEMO_MODE=true` and sets the repository subdirectory base path (`BASE_PATH="/clara-rules-explorer"`).

### Step 3: Preview Locally
You can preview the compiled static build locally before deploying:
```bash
pnpm preview
```
Open the local URL in your browser to inspect the visualizer running 100% statically.

---

## 4. Hosting on GitHub Pages

You have two main choices for hosting this on GitHub Pages:

### Option A: Subdirectory on your main blog domain (Recommended)
If `metasimple.org` is already mapped to your `<username>.github.io` blog repository, this demo will automatically become available at:
👉 `https://metasimple.org/clara-rules-explorer/`

* **DNS Changes**: None.
* **Build Command**: Use the default `pnpm build:demo` (which compiles with `BASE_PATH="/clara-rules-explorer"`).

### Option B: A Dedicated Subdomain (e.g. `explorer.metasimple.org`)
To serve the app on its own dedicated subdomain:
1. In your domain registrar DNS settings, add a `CNAME` record:
   * **Host**: `explorer`
   * **Target**: `mrrodriguez.github.io`
2. In the `clara-rules-explorer` repository on GitHub, go to **Settings > Pages > Custom Domain**, enter `explorer.metasimple.org`, and click **Save**.
3. In `ui/package.json`, change the `build:demo` script to use `BASE_PATH=""` instead of the subdirectory:
   ```json
   "build:demo": "VITE_DEMO_MODE=true BASE_PATH=\"\" vite build"
   ```

---

## 5. Automating Deployment with GitHub Actions

You can automate building and deploying the static demo to the `gh-pages` branch on every push to the `main` branch. 

Create a workflow file at `.github/workflows/deploy-demo.yml`:

```yaml
name: Deploy Demo to GitHub Pages

on:
  push:
    branches: [ "main", "static-ui-demo" ] # Runs when changes hit these branches

permissions:
  contents: write

jobs:
  build-and-deploy:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout repo
        uses: actions/checkout@v4

      - name: Install pnpm
        uses: pnpm/action-setup@v3
        with:
          version: 10

      - name: Install Node.js
        uses: actions/setup-node@v4
        with:
          node-version: 20
          cache: 'pnpm'
          cache-dependency-path: 'ui/pnpm-lock.yaml'

      - name: Build Demo
        run: |
          cd ui
          pnpm install
          pnpm build:demo
        env:
          # Automatically uses correct subfolder for GitHub Pages
          BASE_PATH: '/clara-rules-explorer'

      - name: Deploy to GitHub Pages
        uses: JamesIves/github-pages-deploy-action@v4
        with:
          folder: ui/build
          branch: gh-pages # Deploys static build to the gh-pages branch
```
Once pushed, GitHub Pages will deploy the contents of the `gh-pages` branch automatically.
