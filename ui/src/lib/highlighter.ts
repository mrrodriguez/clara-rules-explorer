import {
	createHighlighter,
	type Highlighter,
	type BundledLanguage,
	type BundledTheme
} from 'shiki';

let highlighterInstance: Highlighter | null = null;
let highlighterPromise: Promise<Highlighter> | null = null;

/**
 * Gets or creates the Shiki highlighter singleton.
 * We use a promise to ensure multiple calls only trigger one initialization.
 */
async function getHighlighter() {
	if (highlighterInstance) return highlighterInstance;

	if (!highlighterPromise) {
		highlighterPromise = createHighlighter({
			themes: ['github-dark', 'github-light'],
			langs: ['clojure', 'json', 'bash', 'markdown']
		});
	}

	highlighterInstance = await highlighterPromise;
	return highlighterInstance;
}

/**
 * Highlights code to HTML using Shiki.
 */
export async function highlight(
	code: string,
	lang: BundledLanguage | (string & {}) = 'clojure',
	theme: BundledTheme | (string & {}) = 'github-dark'
) {
	try {
		const highlighter = await getHighlighter();

		// Ensure the language is loaded
		if (!highlighter.getLoadedLanguages().includes(lang)) {
			await highlighter.loadLanguage(lang as BundledLanguage);
		}

		return highlighter.codeToHtml(code, { lang, theme });
	} catch (error) {
		console.error('Failed to highlight code:', error);
		// Fallback to raw text wrapped in pre/code tags if highlighting fails
		return `<pre class="shiki" style="background-color: #24292e; color: #e1e4e8;"><code>${escapeHtml(code)}</code></pre>`;
	}
}

function escapeHtml(unsafe: string) {
	return unsafe
		.replace(/&/g, '&amp;')
		.replace(/</g, '&lt;')
		.replace(/>/g, '&gt;')
		.replace(/"/g, '&quot;')
		.replace(/'/g, '&#039;');
}
