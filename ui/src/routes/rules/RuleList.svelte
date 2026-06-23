<script lang="ts">
	import type { RuleListItem } from '$lib/types/api';
	import SourceSinkIndicators from '$lib/components/rulebase/SourceSinkIndicators.svelte';
	import UnlinkedRuleIndicator from '$lib/components/rulebase/UnlinkedRuleIndicator.svelte';
	import NoOutputTypesIndicator from '$lib/components/rulebase/NoOutputTypesIndicator.svelte';
	import FilterableNavList from '$lib/components/nav/FilterableNavList.svelte';
	import { rulePath } from '$lib/utils';

	interface Props {
		rules: RuleListItem[];
	}

	let { rules }: Props = $props();
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
	</div>
{/snippet}

<FilterableNavList
	items={rules}
	hrefPrefix={rulePath}
	activeColor="#0d6efd"
	searchPlaceholder="Search rules..."
	itemRight={ruleRight}
/>
