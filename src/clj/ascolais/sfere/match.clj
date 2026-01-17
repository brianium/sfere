(ns ascolais.sfere.match)

(defn wildcard?
  "Returns true if x is the wildcard marker."
  [x]
  (= :* x))

(defn match-key?
  "Returns true if key matches pattern.

   Pattern and key follow structure: [:scope-id [:category :id]]
   Use :* as wildcard to match any value at that position."
  [[p-scope p-inner] [k-scope k-inner]]
  (and (or (wildcard? p-scope) (= p-scope k-scope))
       (or (wildcard? p-inner)
           (let [[p-cat p-id] p-inner
                 [k-cat k-id] k-inner]
             (and (or (wildcard? p-cat) (= p-cat k-cat))
                  (or (wildcard? p-id) (= p-id k-id)))))))
