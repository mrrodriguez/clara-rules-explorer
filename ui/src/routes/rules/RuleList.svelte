<script lang="ts">
	import type { RuleListItem } from '$lib/types/api';
	import SourceSinkIndicators from '$lib/components/rulebase/SourceSinkIndicators.svelte';
	import UnlinkedRuleIndicator from '$lib/components/rulebase/UnlinkedRuleIndicator.svelte';
	import NoOutputTypesIndicator from '$lib/components/rulebase/NoOutputTypesIndicator.svelte';
	import DynamicDetectionIndicator from '$lib/components/rulebase/DynamicDetectionIndicator.svelte';
	import { page } from '$app/state';
	import GroupedFilterableNavList from '$lib/components/nav/GroupedFilterableNavList.svelte';
	import { rulePath } from '$lib/utils';

	interface Props {
		rules: RuleListItem[];
	}

	let { rules }: Props = $props();

	const groupKey = (rule: RuleListItem) => rule.ns;
	const activeId = $derived(page.params.id);
</script>

{#snippet ruleRight(rule: RuleListItem)}
	<div class="d-flex align-items-center gap-1">
		<SourceSinkIndicators
			isSource={rule['source-rule']}
			isSink={rule['sink-rule']}
			variant="icon"
		/>
		<UnlinkedRuleIndicator unlinkedRule={rule['unlinked-rule']} variant="icon" />
		<NoOutputTypesIndicator noOutputTypes={rule['no-output-types']} variant="icon" />
		<DynamicDetectionIndicator
			detection={rule['dynamic-insert-types-detected']}
			label="Inserts"
			variant="icon"
		/>
		<DynamicDetectionIndicator
			detection={rule['dynamic-retract-types-detected']}
			label="Retracts"
			variant="icon"
		/>
	</div>
{/snippet}

<GroupedFilterableNavList
	items={rules}
	{groupKey}
	hrefPrefix={rulePath}
	activeColor="#0d6efd"
	searchPlaceholder="Search rules..."
	itemLabel="rules"
	itemRight={ruleRight}
	{activeId}
/>
