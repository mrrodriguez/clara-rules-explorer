import { describe, it, expect } from 'vitest';
import {
	toRouteId,
	fromRouteId,
	getShortName,
	splitQualifiedName,
	rulePath,
	queryPath,
	factPath
} from './utils';

describe('utils', () => {
	describe('toRouteId', () => {
		it('should replace slash with dot', () => {
			expect(toRouteId('my.ns/my-rule')).toBe('my.ns.my-rule');
		});

		it('should preserve dots in namespace', () => {
			expect(toRouteId('clojure.core/str')).toBe('clojure.core.str');
		});
	});

	describe('fromRouteId', () => {
		it('should restore slash from dot', () => {
			expect(fromRouteId('my.ns.my-rule')).toBe('my.ns/my-rule');
		});

		it('should handle URL-unsafe characters in decoded params', () => {
			// SvelteKit decodes percent-encoded params, so fromRouteId receives
			// the raw characters (e.g., 'my.ns.my-rule?') directly.
			expect(fromRouteId('my.ns.my-rule?')).toBe('my.ns/my-rule?');
		});

		it('should be idempotent for strings without dots', () => {
			expect(fromRouteId('my-rule')).toBe('my-rule');
		});
	});

	describe('rulePath', () => {
		it('should encode URL-unsafe characters', () => {
			expect(rulePath('my.ns/my-rule?')).toBe('/rules/my.ns.my-rule%3F');
		});

		it('should handle full path', () => {
			expect(rulePath('my.ns/my-rule?', true)).toBe('/rules/my.ns.my-rule%3F/full');
		});
	});

	describe('queryPath', () => {
		it('should encode URL-unsafe characters', () => {
			expect(queryPath('my.ns/my-query#test')).toBe('/queries/my.ns.my-query%23test');
		});
	});

	describe('factPath', () => {
		it('should encode URL-unsafe characters', () => {
			expect(factPath('my.ns.MyType?extra')).toBe('/fact-types/my.ns.MyType%3Fextra');
		});
	});

	describe('getShortName', () => {
		it('should extract short name from fully-qualified name', () => {
			expect(
				getShortName('clara.server.tools.graph.rules.loan-app-rules/collect-app-given-docs')
			).toBe('collect-app-given-docs');
		});
	});

	describe('splitQualifiedName', () => {
		it('should split Clojure qualified name', () => {
			expect(splitQualifiedName('my.ns/my-rule')).toEqual({
				name: 'my-rule',
				namespace: 'my.ns'
			});
		});

		it('should split Java qualified name', () => {
			expect(splitQualifiedName('my.ns.MyClass')).toEqual({
				name: 'MyClass',
				namespace: 'my.ns'
			});
		});

		it('should return empty namespace for unqualified name', () => {
			expect(splitQualifiedName('my-rule')).toEqual({
				name: 'my-rule',
				namespace: ''
			});
		});
	});
});
