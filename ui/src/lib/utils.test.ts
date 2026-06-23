import { describe, it, expect } from 'vitest';
import { toUrlId, fromUrlId, getShortName, splitQualifiedName } from './utils';

describe('utils', () => {
	describe('toUrlId', () => {
		it('should replace slash with dot', () => {
			expect(toUrlId('my.ns/my-rule')).toBe('my.ns.my-rule');
		});
	});

	describe('fromUrlId', () => {
		it('should restore slash from dot', () => {
			expect(fromUrlId('my.ns.my-rule')).toBe('my.ns/my-rule');
		});

		it('should be idempotent for strings without dots', () => {
			expect(fromUrlId('my-rule')).toBe('my-rule');
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
