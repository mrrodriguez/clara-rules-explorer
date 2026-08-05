import { describe, it, expect } from 'vitest';
import { getShortName, rulePath, queryPath, factPath, splitDisplayName } from './utils';

describe('utils', () => {
	describe('rulePath', () => {
		it('passes the server-issued id through verbatim', () => {
			expect(rulePath('my.ns.my-rule-a1b2c3d4')).toBe('/rules/my.ns.my-rule-a1b2c3d4');
		});

		it('handles full path', () => {
			expect(rulePath('my.ns.my-rule-a1b2c3d4', true)).toBe('/rules/my.ns.my-rule-a1b2c3d4/full');
		});

		it('handles keyword-derived ids with hyphens', () => {
			expect(rulePath('my.ns.verify-docs-q2w8e5r4')).toBe('/rules/my.ns.verify-docs-q2w8e5r4');
		});
	});

	describe('queryPath', () => {
		it('passes the server-issued id through verbatim', () => {
			expect(queryPath('my.ns.find-cold-c3d5e7f9')).toBe('/queries/my.ns.find-cold-c3d5e7f9');
		});

		it('handles full path', () => {
			expect(queryPath('my.ns.find-cold-c3d5e7f9', true)).toBe(
				'/queries/my.ns.find-cold-c3d5e7f9/full'
			);
		});
	});

	describe('factPath', () => {
		it('passes the server-issued id through verbatim', () => {
			expect(factPath('my.ns.MarkerRecord-a1b2c3d4')).toBe(
				'/fact-types/my.ns.MarkerRecord-a1b2c3d4'
			);
		});

		it('handles tuple-derived ids with dots', () => {
			expect(factPath('loan.status.verified-k4x9p2m8')).toBe(
				'/fact-types/loan.status.verified-k4x9p2m8'
			);
		});
	});

	describe('getShortName', () => {
		it('should extract short name from fully-qualified name', () => {
			expect(
				getShortName('clara.server.tools.graph.rules.loan-app-rules/collect-app-given-docs')
			).toBe('collect-app-given-docs');
		});

		it('returns the name unchanged for kind-explicit keyword types', () => {
			expect(getShortName(':my.ns/child')).toBe('child');
		});

		it('returns the name unchanged when no slash is present', () => {
			expect(getShortName('MarkerRecord')).toBe('MarkerRecord');
		});
	});

	describe('splitDisplayName', () => {
		it('splits a Clojure production name on the last slash', () => {
			expect(splitDisplayName('my.ns/verify-docs?')).toEqual({
				name: 'verify-docs?',
				namespace: 'my.ns'
			});
		});

		it('splits a class name on the last dot', () => {
			expect(splitDisplayName('my.ns.MarkerRecord')).toEqual({
				name: 'MarkerRecord',
				namespace: 'my.ns'
			});
		});

		it('keeps the colon on the namespace for keyword types', () => {
			expect(splitDisplayName(':my.ns/child')).toEqual({
				name: 'child',
				namespace: ':my.ns'
			});
		});

		it('does not split string types (quotes preserved)', () => {
			expect(splitDisplayName('"foo"')).toEqual({ name: '"foo"', namespace: '' });
		});

		it('does not split tuple types', () => {
			expect(splitDisplayName('[:loan/status "verified"]')).toEqual({
				name: '[:loan/status "verified"]',
				namespace: ''
			});
		});

		it('does not split unresolved symbols', () => {
			expect(splitDisplayName('symbol[my.ns/foo]')).toEqual({
				name: 'symbol[my.ns/foo]',
				namespace: ''
			});
		});

		it('returns an empty namespace for unqualified names', () => {
			expect(splitDisplayName('my-rule')).toEqual({ name: 'my-rule', namespace: '' });
		});
	});
});
