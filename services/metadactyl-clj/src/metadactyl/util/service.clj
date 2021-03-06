(ns metadactyl.util.service
  (:use [clojure.java.io :only [reader]]
        [clojure.string :only [join upper-case]]
        [ring.util.response :only [charset]]
        [slingshot.slingshot :only [try+ throw+]])
  (:require [cheshire.core :as cheshire]
            [clojure.tools.logging :as log]
            [clojure-commons.error-codes :as ce]))

(def ^:private default-content-type-header
  {"Content-Type" "application/json; charset=utf-8"})

(defn success-response
  ([map]
     (charset
      {:status       200
       :body         map
       :headers default-content-type-header}
      "UTF-8"))
  ([]
     (success-response nil)))

(defn unrecognized-path-response []
  "Builds the response to send for an unrecognized service path."
  (let [msg "unrecognized service path"]
    (cheshire/encode {:reason msg})))

(defn build-url
  "Builds a URL from a base URL and one or more URL components."
  [base & components]
  (join "/" (map #(.replaceAll % "^/|/$" "")
                 (cons base components))))

(defn prepare-forwarded-request
  "Prepares a request to be forwarded to a remote service."
  [request body]
  {:headers (dissoc (:headers request) "content-length")
   :body body})

(defn parse-json
  "Parses a JSON request body."
  [body]
  (try+
    (if (string? body)
      (cheshire/decode body true)
      (cheshire/decode-stream (reader body) true))
    (catch Exception e
      (throw+ {:error_code ce/ERR_INVALID_JSON
               :detail     (str e)}))))
