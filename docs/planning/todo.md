1. hierarchy shows ancestors in UI, but no descendants which is also useful.
2. Spend time understand callsite "via" chain more since I still get confused why some calls do not
   have one such as:
   ```
   (def my-fn [x] (insert! (->fact x)))
   (defrule my-rule => (my-fn :stuff))
   ```
   where ->fact has a handler given.
