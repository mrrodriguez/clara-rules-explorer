import type { ContextualMenuType } from './types/ui';

export const CONTEXTUAL_MENU_CONFIG: Record<
	ContextualMenuType,
	{
		label: string;
		icon: string;
		navKey: 'upstream' | 'downstream' | 'inputTypes' | 'insertTypes' | 'retractTypes';
		contentType: 'production' | 'fact';
	}
> = {
	upstream: {
		label: 'Upstream',
		icon: 'bi-arrow-up-circle',
		navKey: 'upstream',
		contentType: 'production'
	},
	downstream: {
		label: 'Downstream',
		icon: 'bi-arrow-down-circle',
		navKey: 'downstream',
		contentType: 'production'
	},
	input: {
		label: 'Input Types',
		icon: 'bi-box-arrow-in-right',
		navKey: 'inputTypes',
		contentType: 'fact'
	},
	insert: {
		label: 'Insert Types',
		icon: 'bi-box-arrow-right',
		navKey: 'insertTypes',
		contentType: 'fact'
	},
	retract: {
		label: 'Retract Types',
		icon: 'bi-dash-circle',
		navKey: 'retractTypes',
		contentType: 'fact'
	}
};
