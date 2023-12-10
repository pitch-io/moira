(ns moira.event)

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn read-event-id [form]
  `(if (string? ~form)
     (moira.event/string->event-id ~form)
     (throw (js/Error. "EventId literal expects a string as its representation."))))
