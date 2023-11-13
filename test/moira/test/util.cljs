(ns moira.test.util)

(defn peek-seq [coll]
  (if (empty? coll)
    coll
    (cons (peek coll) (peek-seq (pop coll)))))
