(ns metadactyl.routes.domain.app.category
  (:use [metadactyl.routes.domain.app]
        [metadactyl.routes.params]
        [ring.swagger.schema :only [describe]]
        [schema.core :only [defschema optional-key Any]])
  (:import [java.util UUID]))

(defschema AppCategory
  {:id
   AppCategoryIdPathParam

   :name
   (describe String "The App Category's name")

   :description
   (describe String "The App Category's description")

   :app_count
   (describe Long "The number of Apps under this Category and all of its children")

   :workspace_id
   (describe UUID "The ID of this App Category's Workspace")

   :is_public
   (describe Boolean
     "Whether this App Category is viewable to all users or private to only the user that owns its
      Workspace")

   ;; KLUDGE
   :groups
   (describe [Any]
     "A listing of child App Categories under this App Category.
      <b>Note</b>: This will be a list of more categories like this one, but the documentation
      library does not currently support recursive model schema definitions")})

(defschema AppCategoryListing
  {:groups (describe [AppCategory] "A listing of App Categories visisble to the requesting user")})

(defschema AppCategoryAppListing
  (merge (dissoc AppCategory :workspace_id :groups)
         {:apps (describe [AppDetails] "A listing of Apps under this Category")}))