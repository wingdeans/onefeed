(require '[clojure.zip :as zip]
         '[babashka.http-client :as http]
         '[hickory.core :as hickory]
         '[hickory.select :as sel]
         '[hickory.zip :refer [hickory-zip]]
         '[hiccup2.core :as hiccup])

(def cookie
  (let [hn-username (System/getenv "HN_USERNAME")
        hn-password (System/getenv "HN_PASSWORD")]
    (when (and hn-username hn-password)
      (-> (http/post "https://news.ycombinator.com/login"
                     {:client (http/client
                               (conj http/default-client-opts
                                     {:follow-redirects :never}))
                      :form-params {:acct hn-username
                                    :pw hn-password}})
          :headers
          (get "set-cookie")
          (clojure.string/split #";")
          first))))

(defn rezip-node [loc]
  (hickory-zip (zip/node loc)))

(def sel-submission (sel/and (sel/class :athing)
                             (sel/class :submission)))

(defn parse-submission [submission]
  (let [titleline-child
        (->> submission
             rezip-node
             (sel/select-next-loc (sel/class :titleline))
             zip/down)
        titleline-child-node (zip/node titleline-child)
        flagged (and (string? titleline-child-node)
                     (clojure.string/includes?
                      titleline-child-node "flagged"))
        title (if flagged
                (zip/node (zip/next titleline-child))
                titleline-child-node)
        [score user age unv comments]
        (->> submission
             zip/right
             rezip-node
             (sel/select-next-loc (sel/class :subline))
             zip/children
             (filter #(= :element (:type %))))]
    {:title (str (if flagged "[flagged] " "")
                 (first (:content title)))
     :url (:href (:attrs title))
     :score (:content score)
     :comments (:content comments)}))

(defn hn-submissions [date page]
  (let [url (format
             "https://news.ycombinator.com/front?day=%s&p=%s"
             date page)
        submission (->> (http/get url (if cookie
                                        {:headers {:cookie cookie}}
                                        {}))
                        :body
                        hickory/parse
                        hickory/as-hickory
                        hickory-zip
                        (sel/select-next-loc (sel/id :bigbox))
                        rezip-node
                        (sel/select-next-loc sel-submission))]
    (loop [submission submission
           acc (transient [])]
      (if (nil? submission)
        (persistent! acc)
        (recur
         (sel/select-next-loc sel-submission
                              (zip/right (zip/right submission)))
         (conj! acc (parse-submission submission)))))))

(let [yesterday (-> (java.time.ZoneId/of "UTC")
                    java.time.LocalDate/now
                    (.minusDays 1))
      submissions (->> (pmap #(hn-submissions yesterday %)
                             (range 1 2))
                       flatten
                       (map (fn [submission]
                              [:a {:href (:url submission)}
                               (:title submission)])))
      html
      (str
       (hiccup/html
        (hiccup/raw "<!DOCTYPE html>")
        [:html
         [:head
          [:link {:rel "stylesheet"
                  :href "resources/styles.css"}]]
         [:div#submissions submissions]]))]
  (with-open [w (clojure.java.io/writer
                 (str yesterday ".html"))]
    (.write w html))
  (with-open [w (clojure.java.io/writer "index.html")]
    (.write w html)))
