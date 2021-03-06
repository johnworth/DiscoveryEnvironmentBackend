(ns metadactyl.routes.domain.pipeline
  (:use [metadactyl.routes.params]
        [metadactyl.routes.domain.app :only [AppTaskListing]]
        [ring.swagger.schema :only [describe]]
        [schema.core :only [defschema optional-key Any]])
  (:import [java.util UUID]))

(defschema PipelineMapping
  {:source_step (describe Long "The step index of the Source Step")
   :target_step (describe Long "The step index of the Target Step")
   ;; KLUDGE
   :map (describe Any "The {'input-uuid': 'output-uuid'} mapping")})

(defschema PipelineStep
  {:name
   (describe String "The Step's name")

   :description
   (describe String "The Step's description")

   (optional-key :task_id)
   (describe UUID "A UUID that is used to identify this Step's Task. This field is required any
                   time the external app ID isn't provided.")

   (optional-key :external_app_id)
   (describe String "A string referring to an external app that is used to perform the step. This
                     field is required any time the task ID isn't provided.")

   :app_type
   (describe String "The Step's App type")})

(defschema Pipeline
  (merge AppTaskListing
    {:steps
     (describe [PipelineStep] "The Pipeline's steps")

     :mappings
     (describe [PipelineMapping] "The Pipeline's input/output mappings")}))

(defschema PipelineUpdateRequest
  (->optional-param Pipeline :tasks))

(defschema PipelineCreateRequest
  (->optional-param PipelineUpdateRequest :id))
