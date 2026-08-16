import { describe, it, expect } from 'vitest';
import { buildViaEntries } from './viaChain';
import type { ViaChain } from '$lib/types/api';

const rule = 'clara.server.tools.graph.rules.loan-doc-rules/collect-app-doc-check-input';
const helper = 'clara.server.tools.graph.rules.loan-doc-rules/insert-document-check-input!';
const builder = 'clara.server.tools.graph.rules.loan-doc-rules/->document-check-input';
const ctor = 'clara.server.tools.graph.rules.helpers/->fact';
const boundary = 'clara.rules/insert!';

describe('buildViaEntries', () => {
	it('renders the RHS-only shape (boundary call in the rule itself)', () => {
		const via: ViaChain = {
			'boundary-var-name-sym': boundary,
			'boundary-to-constructor-path': [
				{ 'var-name-sym': rule },
				{ 'var-name-sym': builder },
				{ 'var-name-sym': ctor }
			]
		};

		expect(buildViaEntries(via)).toEqual([
			{ label: 'boundary', sym: boundary },
			{ label: 'caller', sym: rule },
			{ label: 'caller', sym: builder },
			{ label: 'constructor', sym: ctor }
		]);
	});

	it('renders the helper + constructor shape (both paths, shared join shown once)', () => {
		const via: ViaChain = {
			'boundary-var-name-sym': boundary,
			'boundary-in-var': helper,
			'rule-to-boundary-path': [{ 'var-name-sym': rule }, { 'var-name-sym': helper }],
			'boundary-to-constructor-path': [
				{ 'var-name-sym': helper },
				{ 'var-name-sym': builder },
				{ 'var-name-sym': ctor }
			]
		};

		expect(buildViaEntries(via)).toEqual([
			{ label: 'rule', sym: rule },
			{ label: 'caller', sym: helper },
			{ label: 'boundary', sym: boundary },
			{ label: 'caller', sym: builder },
			{ label: 'constructor', sym: ctor }
		]);
	});

	it('renders the helper-without-constructor shape (rule-side path only)', () => {
		const via: ViaChain = {
			'boundary-var-name-sym': boundary,
			'boundary-in-var': helper,
			'rule-to-boundary-path': [{ 'var-name-sym': rule }, { 'var-name-sym': helper }]
		};

		expect(buildViaEntries(via)).toEqual([
			{ label: 'rule', sym: rule },
			{ label: 'caller', sym: helper },
			{ label: 'boundary', sym: boundary }
		]);
	});

	it('returns nothing for a pathless heuristic via (record-ctor scan)', () => {
		expect(buildViaEntries({ source: 'record-ctor-scan' })).toEqual([]);
	});
});
