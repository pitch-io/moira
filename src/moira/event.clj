(ns moira.event)

(defn read-event-id [form]
  `(if (string? ~form)
     (moira.event/string->event-id ~form)
     (throw (js/Error. "EventId literal expects a string as its representation."))))
