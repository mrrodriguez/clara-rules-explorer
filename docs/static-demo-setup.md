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

The demo is configured to be hosted as a subdirectory of your main domain, `metasimple.org`, which is already connected to your `<username>.github.io` blog repository. 

Once deployed, the explorer demo will automatically be served at:
👉 **`https://metasimple.org/clara-rules-explorer/`**

* **DNS Configuration**: None required.
* **Build Path**: Compiled using `BASE_PATH="/clara-rules-explorer"` (this is handled automatically by `pnpm build:demo`).

---

## 5. Automating Deployment with GitHub Actions

You can automate building and deploying the static demo to the `gh-pages` branch on every push to the `main` branch. 

The build and deploy pipeline is configured in [.github/workflows/deploy-demo.yml](file:///Users/mrrodriguez/Projects/clara-rules-explorer/.github/workflows/deploy-demo.yml). 

Once pushed, GitHub Actions will build your UI statically and deploy the static artifacts to the `gh-pages` branch, which GitHub Pages will serve automatically.
