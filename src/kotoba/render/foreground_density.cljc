(ns kotoba.render.foreground-density
  "Authored foreground/midground density and material layering descriptors."
  (:require [kotoba.render.mesh :as mesh]
            [kotoba.render.procedural :as procedural]
            [kotoba.render.vegetation :as vegetation]))

(def schema :kotoba.render/foreground-density-v1)
(def families #{:stylized :photoreal})
(def tiers #{:hero :mid :background})
(def material-contract :kotoba.render/material-preset-v1)

(def tier-policy
  {:hero {:instance-budget 36 :draw-budget 42 :triangle-budget 3200
          :foreground-count 18 :midground-count 12}
   :mid {:instance-budget 24 :draw-budget 28 :triangle-budget 1800
         :foreground-count 10 :midground-count 8}
   :background {:instance-budget 14 :draw-budget 16 :triangle-budget 720
                :foreground-count 3 :midground-count 5}})

(def kinds [:shrub :grass :crate :bollard :rock :debris])
(def vegetation-kinds [:shrub :grass])
(def solid-kinds [:crate :bollard :rock :debris])

(def ^:private ground-contact-screen-y-ranges
  {:foreground [0.58 0.90]
   :midground [0.42 0.72]})

(def ^:private screen-extent-ranges
  {:shrub [0.06 0.16]
   :grass [0.025 0.11]
   :crate [0.04 0.10]
   :bollard [0.018 0.05]
   :rock [0.04 0.11]
   :debris [0.025 0.075]})

(def ^:private kind-profile
  {:shrub {:size [2.2 1.5 1.9] :role :foliage}
   :grass {:size [1.1 0.72 0.9] :role :grass}
   :crate {:size [0.85 0.78 0.85] :role :utility}
   :bollard {:size [0.24 0.86 0.24] :role :utility}
   :rock {:size [0.95 0.62 0.78] :role :trunk}
   :debris {:size [0.62 0.16 0.34] :role :utility}})

(def material-records
  {:foliage {:base-color [0.16 0.42 0.20 1.0] :metallic 0.0 :roughness 0.88}
   :grass {:base-color [0.22 0.48 0.16 1.0] :metallic 0.0 :roughness 0.92}
   :trunk {:base-color [0.25 0.17 0.10 1.0] :metallic 0.0 :roughness 0.95}
   :utility {:base-color [0.20 0.23 0.27 1.0] :metallic 0.46 :roughness 0.48}
   :road-edge-wear {:base-color [0.22 0.20 0.17 0.72] :metallic 0.0 :roughness 0.97}
   :road-patch {:base-color [0.045 0.05 0.06 0.92] :metallic 0.0 :roughness 0.94}
   :road-decal {:base-color [0.72 0.58 0.24 0.78] :metallic 0.0 :roughness 0.78}
   :facade-base {:base-color [0.34 0.27 0.22 1.0] :metallic 0.02 :roughness 0.88}
   :facade-trim {:base-color [0.52 0.38 0.21 1.0] :metallic 0.16 :roughness 0.58}
   :facade-window {:base-color [0.045 0.38 0.54 1.0] :metallic 0.10 :roughness 0.18
                   :emissive [0.01 0.18 0.30] :emissive-strength 0.54}})

(defn- unit [seed salt]
  (/ (double (bit-and (procedural/coordinate-hash seed salt 23 251) 65535)) 65535.0))

(defn- bounds [positions]
  (let [p3 (partition 3 positions)]
    {:min (mapv #(apply min (map (fn [p] (nth p %)) p3)) (range 3))
     :max (mapv #(apply max (map (fn [p] (nth p %)) p3)) (range 3))}))

(defn normalize-mesh
  "Normalize any mesh tuple to X/Z [-.5,.5], grounded Y [0,1]. The transform
   is then the sole world-size owner, preventing authored-size double scaling."
  [[positions normals uvs indices]]
  (let [{[min-x min-y min-z] :min [max-x max-y max-z] :max} (bounds positions)
        extents [(max 1.0e-9 (- max-x min-x)) (max 1.0e-9 (- max-y min-y))
                 (max 1.0e-9 (- max-z min-z))]
        [ex ey ez] extents
        cx (/ (+ min-x max-x) 2.0) cz (/ (+ min-z max-z) 2.0)
        normalized (vec (mapcat (fn [[x y z]]
                                  [(/ (- x cx) ex) (/ (- y min-y) ey) (/ (- z cz) ez)])
                                (partition 3 positions)))]
    {:mesh [normalized normals uvs indices]
     :geometry-space :normalized-unit
     :normalized-bounds {:min [-0.5 0.0 -0.5] :max [0.5 1.0 0.5]}
     :source-bounds {:min [min-x min-y min-z] :max [max-x max-y max-z]}}))

(defn- source-mesh [kind seed]
  (case kind
    :shrub (vegetation/vegetation-mesh
            {:variant :shrub :width 2.2 :depth 1.9 :height 1.5 :seed seed} :low)
    :grass (vegetation/vegetation-mesh
            {:variant :grass-tuft :width 1.1 :depth 0.9 :height 0.72 :seed seed} :low)
    :bollard (mesh/cylinder-pipe 0.5 0.0 1.0 8)
    :rock (mesh/sphere 4 7)
    (mesh/cube)))

(defn- material-ref [family role entity-id]
  (let [preset-role (case role :foliage :foliage :grass :grass :trunk :trunk
                          :facade-trim :trim :facade-window :window
                          :facade-base :wall :utility)]
    {:contract material-contract :family family
     :preset-id (keyword (name family)
                         (str (if (#{:foliage :grass :trunk} preset-role)
                                "vegetation-" "architecture-")
                              (name preset-role)))
     :domain (if (#{:foliage :grass :trunk} preset-role) :vegetation :architecture)
     :role preset-role :entity-id entity-id}))

(defn- geometry-library [seed]
  (into {} (map-indexed (fn [index kind]
                          [kind (normalize-mesh (source-mesh kind (+ seed index)))])
                        kinds)))

(defn- normalize-facing-direction [direction]
  (when direction
    (when-not (and (sequential? direction) (= 2 (count direction))
                   (every? number? direction))
      (throw (ex-info "camera-facing-direction must be a 2D X/Z vector"
                      {:camera-facing-direction direction})))
    (let [[x z] direction
          length (#?(:clj Math/sqrt :cljs js/Math.sqrt) (+ (* x x) (* z z)))]
      (when (<= length 1.0e-9)
        (throw (ex-info "camera-facing-direction must be non-zero"
                        {:camera-facing-direction direction})))
      [(/ x length) (/ z length)])))

(defn- descriptor [family seed index zone origin radius ground-y camera-facing-direction]
  (let [cluster-role (if (even? (quot index 2)) :vegetation :solid-prop)
        role-kinds (if (= cluster-role :vegetation) vegetation-kinds solid-kinds)
        kind (nth role-kinds
                  (mod (bit-and (procedural/coordinate-hash seed index 31 337) 65535)
                       (count role-kinds)))
        role (:role (kind-profile kind))
        angle (* 6.283185307179586 (unit seed (+ 100 index)))
        r (* radius (+ 0.18 (* 0.78 (unit seed (+ 200 index)))))
        cos #?(:clj Math/cos :cljs js/Math.cos) sin #?(:clj Math/sin :cljs js/Math.sin)
        [ox _ oz] origin
        scale-factor (+ 0.86 (* 0.28 (unit seed (+ 300 index))))
        size (mapv #(* % scale-factor) (:size (kind-profile kind)))
        screen-side (if (even? index) :left :right)
        region (keyword (str (name zone) "-" (name screen-side)))
        cluster-id (keyword (str (name zone) "-" (name screen-side) "-cluster"))
        radial [(* r (cos angle)) (* r (sin angle))]
        camera-facing? (and (= zone :foreground) (= cluster-role :vegetation)
                            camera-facing-direction)
        [dx dz] (if camera-facing?
                  (let [[fx fz] camera-facing-direction
                        projection (+ (* (first radial) fx) (* (second radial) fz))
                        lateral [(- (first radial) (* projection fx))
                                 (- (second radial) (* projection fz))]
                        facing-depth (max (* radius 0.18)
                                          (#?(:clj Math/abs :cljs js/Math.abs) projection))]
                    [(+ (first lateral) (* facing-depth fx))
                     (+ (second lateral) (* facing-depth fz))])
                  radial)]
    {:descriptor/id (keyword (str (name zone) "-" (name kind) "-" index))
     :camera-zone zone :composition-region region :screen-side screen-side
     :ground-contact-screen-y-range (ground-contact-screen-y-ranges zone)
     :screen-extent-range (screen-extent-ranges kind)
     :camera-facing-direction camera-facing-direction
     :cluster-id cluster-id :cluster-role cluster-role
     :kind kind :geometry-ref kind
     :geometry-space :normalized-unit
     :material-role role :material (material-records role)
     :material-ref (material-ref family role (str (name zone) "/" index))
     :transform {:offset [(+ ox dx) ground-y (+ oz dz)]
                 :scale size :scale-mode :world-size
                 :rotation [0.0 (* 6.283185307179586 (unit seed (+ 400 index))) 0.0]
                 :grounded? true}
     :collision {:mode :none :visual-only? true}}))

(defn- layer-descriptor [family index role offset scale geometry-ref clearance attachment]
  {:descriptor/id (keyword (str "material-layer-" index))
   :camera-zone :foreground :kind :material-layer
   :geometry-ref geometry-ref :geometry-space :normalized-unit
   :material-role role :material (material-records role)
   :material-ref (material-ref family role (str "layer/" index))
   :transform {:offset (update offset 1 + clearance) :scale scale
               :scale-mode :world-size :rotation [0.0 0.0 0.0] :grounded? true}
   :collision {:mode :none :visual-only? true}
   :attachment attachment
   :layering {:projection (if (#{:road-edge-wear :road-patch :road-decal} role)
                            :ground :facade)
              :depth-bias clearance :alpha-mode :blend}})

(defn- material-layers [family origin ground-y]
  (let [[x _ z] origin]
    [(layer-descriptor family 0 :road-edge-wear [(- x 3.2) ground-y z] [5.2 0.01 0.72] :crate 0.012
                       {:target :road-surface :space :neighborhood-world :anchor :road-edge})
     (layer-descriptor family 1 :road-patch [(+ x 2.1) ground-y (+ z 1.4)] [2.4 0.01 1.6] :crate 0.014
                       {:target :road-surface :space :neighborhood-world :anchor :carriageway})
     (layer-descriptor family 2 :road-decal [x ground-y (- z 2.2)] [3.0 0.01 0.32] :crate 0.016
                       {:target :road-surface :space :neighborhood-world :anchor :lane})
     ;; Facade offsets are local to the consuming building facade. They are not
     ;; fake world coordinates relative to the neighborhood origin.
     (layer-descriptor family 3 :facade-base [0.0 0.0 0.05] [4.0 2.2 0.10] :crate 0.0
                       {:target :building-facade :space :facade-local :anchor :base})
     (layer-descriptor family 4 :facade-trim [0.0 1.7 0.08] [4.2 0.20 0.08] :crate 0.0
                       {:target :building-facade :space :facade-local :anchor :trim-band})
     (layer-descriptor family 5 :facade-window [0.0 0.72 0.13] [1.3 0.82 0.05] :crate 0.0
                       {:target :building-facade :space :facade-local :anchor :window-bay})]))

(defn- budget [descriptors layers library tier]
  (let [triangles (+ (reduce + 0 (map #(quot (count (nth (get-in library [(:geometry-ref %) :mesh]) 3)) 3)
                                      descriptors))
                     (* 12 (count layers)))
        draws (+ (count descriptors) (count layers)) policy (tier-policy tier)]
    {:instances (+ (count descriptors) (count layers)) :instance-budget (:instance-budget policy)
     :draws draws :draw-budget (:draw-budget policy)
     :triangles triangles :triangle-budget (:triangle-budget policy)
     :within-budget? (and (<= (+ (count descriptors) (count layers)) (:instance-budget policy))
                          (<= draws (:draw-budget policy))
                          (<= triangles (:triangle-budget policy)))}))

(defn foreground-kit
  "Resolve density descriptors. `:camera-facing-direction` is an optional X/Z
   ground-plane vector from the composition origin/target toward the camera;
   it is not the camera's forward/look direction. Omit it to preserve radial
   placement."
  [{:keys [family tier entity-id seed origin radius ground-y camera-facing-direction]
    :or {family :stylized tier :mid entity-id :foreground seed 0
         origin [0.0 0.0 0.0] radius 10.0 ground-y 0.0}}]
  (when-not (families family) (throw (ex-info "unsupported foreground family" {:family family})))
  (when-not (tiers tier) (throw (ex-info "unsupported foreground tier" {:tier tier})))
  (if (= family :photoreal)
    {:schema schema :family family :tier tier :entity-id entity-id
     :implementation-status :boundary-only :quality-claim :unsupported-future
     :geometry-library {} :camera-zones {} :material-layers [] :budget {:within-budget? true}}
    (let [policy (tier-policy tier) library (geometry-library seed)
          facing-direction (normalize-facing-direction camera-facing-direction)
          foreground (mapv #(descriptor family seed % :foreground origin (* radius 0.62) ground-y
                                        facing-direction)
                           (range (:foreground-count policy)))
          midground (mapv #(descriptor family seed (+ 100 %) :midground origin radius ground-y
                                       facing-direction)
                          (range (:midground-count policy)))
          all (vec (concat foreground midground)) layers (material-layers family origin ground-y)]
      {:schema schema :family family :tier tier :entity-id entity-id
       :implementation-status :implemented :quality-claim :stylized-authored
       :geometry-contract {:space :normalized-unit :bounds {:min [-0.5 0.0 -0.5] :max [0.5 1.0 0.5]}
                           :transform-scale-mode :world-size :double-scale-forbidden? true}
       :placement-contract {:camera-facing-direction facing-direction
                            :fallback :preserve-radial-layout}
       :geometry-library library
       :camera-zones {:foreground foreground :midground midground}
       :material-layers layers :budget (budget all layers library tier)})))

(defn foreground-lods [spec]
  (mapv #(foreground-kit (assoc spec :tier %)) [:hero :mid :background]))
