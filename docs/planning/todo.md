1. hierarchy shows ancestors in UI, but no descendants which is also useful.

2. resolve-constructor-callsites is a mess of long fn complexity with nested looping for the `result` binding. refactor it and split to meaningful names for the complex transformation functions involved.
