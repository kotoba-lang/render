(ns kotoba.render.vegetation
  "Deterministic vegetation silhouettes and WebGPU-ready registration data."
  (:require [kotoba.render.mesh :as mesh]
            [kotoba.render.procedural :as procedural]))

(def variants #{:broadleaf :conifer :shrub})
(def details #{:high :low})

(defn- validate! [{:keys [variant width depth height seed]} detail]
  (when-not (variants variant)
    (throw (ex-info "unsupported vegetation variant" {:variant variant :supported variants})))
  (when-not (details detail)
    (throw (ex-info "unsupported vegetation detail" {:detail detail :supported details})))
  (when-not (and (number? width) (pos? width) (number? depth) (pos? depth)
                 (number? height) (pos? height))
    (throw (ex-info "vegetation dimensions must be positive" {:width width :depth depth :height height})))
  (when-not (and (integer? seed) (<= 0 seed 4294967295))
    (throw (ex-info "vegetation seed must be an unsigned 32-bit integer" {:seed seed}))))

(defn- combine [meshes]
  (reduce (fn [[ps ns us is] [p n u idx]]
            (let [base (quot (count ps) 3)]
              [(into ps p) (into ns n) (into us u) (into is (map #(+ base %) idx))]))
          [[] [] [] []] meshes))

(defn- transform [[positions normals uvs indices] scale [tx ty tz]]
  (let [[sx sy sz] scale
        transformed (vec (mapcat (fn [[x y z]]
                                   [(+ tx (* sx x)) (+ ty (* sy y)) (+ tz (* sz z))])
                                 (partition 3 positions)))
        transformed-normals
        (vec (mapcat (fn [[x y z]]
                       (let [nx (/ x sx) ny (/ y sy) nz (/ z sz)
                             length (#?(:clj Math/sqrt :cljs js/Math.sqrt)
                                     (+ (* nx nx) (* ny ny) (* nz nz)))]
                         (if (pos? length) [(/ nx length) (/ ny length) (/ nz length)]
                             [0.0 1.0 0.0])))
                     (partition 3 normals)))]
    [transformed transformed-normals uvs indices]))

(defn- sphere-part [radius center stacks slices]
  (transform (mesh/sphere stacks slices) [(* radius 2.0) (* radius 2.0) (* radius 2.0)] center))

(defn- trunk-part [radius height sectors]
  (transform (mesh/cylinder-pipe radius 0.0 height sectors) [1.0 1.0 1.0]
             [0.0 (/ height 2.0) 0.0]))

(defn- normalize [[x y z]]
  (let [length (#?(:clj Math/sqrt :cljs js/Math.sqrt) (+ (* x x) (* y y) (* z z)))]
    (if (pos? length) [(/ x length) (/ y length) (/ z length)] [0.0 1.0 0.0])))

(defn- cone [radius height sectors center-y]
  (let [pi #?(:clj Math/PI :cljs js/Math.PI)
        sin #?(:clj #(Math/sin %) :cljs #(js/Math.sin %))
        cos #?(:clj #(Math/cos %) :cljs #(js/Math.cos %))
        triangles
        (mapcat
         (fn [i]
           (let [a0 (/ (* 2.0 pi i) sectors) a1 (/ (* 2.0 pi (inc i)) sectors)
                 p0 [(* radius (cos a0)) center-y (* radius (sin a0))]
                 p1 [(* radius (cos a1)) center-y (* radius (sin a1))]
                 apex [0.0 (+ center-y height) 0.0]
                 mid (/ (+ a0 a1) 2.0)
                 side-normal (normalize [(cos mid) (/ radius height) (sin mid)])]
             [{:p apex :n side-normal :uv [0.5 0.0]}
              {:p p0 :n side-normal :uv [(/ (double i) sectors) 1.0]}
              {:p p1 :n side-normal :uv [(/ (double (inc i)) sectors) 1.0]}
              {:p [0.0 center-y 0.0] :n [0.0 -1.0 0.0] :uv [0.5 0.5]}
              {:p p1 :n [0.0 -1.0 0.0] :uv [1.0 1.0]}
              {:p p0 :n [0.0 -1.0 0.0] :uv [0.0 1.0]}]))
         (range sectors))
        vertices (vec triangles)]
    [(vec (mapcat :p vertices)) (vec (mapcat :n vertices)) (vec (mapcat :uv vertices))
     (vec (range (count vertices)))]))

(defn- seed-offset [seed salt extent]
  (* extent (- (/ (double (bit-and (procedural/coordinate-hash seed salt 0 83) 255)) 255.0) 0.5)))

(defn- broadleaf [{:keys [width depth height seed]} detail]
  (let [trunk-h (* height 0.55) trunk-r (* 0.06 (min width depth))
        crown-r (min (* width 0.24) (* depth 0.24) (* height 0.22))
        crown-y (- height crown-r)
        trunk (trunk-part trunk-r trunk-h (if (= detail :high) 8 5))]
    (if (= detail :low)
      [trunk (sphere-part crown-r [0.0 crown-y 0.0] 4 6)]
      [trunk
       (sphere-part crown-r [0.0 crown-y 0.0] 6 10)
       (sphere-part (* crown-r 0.78)
                    [(seed-offset seed 1 (* width 0.22)) (* height 0.67)
                     (seed-offset seed 2 (* depth 0.18))] 5 8)
       (sphere-part (* crown-r 0.72)
                    [(seed-offset seed 3 (* width 0.20)) (* height 0.61)
                     (seed-offset seed 4 (* depth 0.22))] 5 8)])))

(defn- conifer [{:keys [width depth height]} detail]
  (let [radius (* 0.5 (min width depth))
        trunk (trunk-part (* radius 0.14) (* height 0.28) (if (= detail :high) 8 5))]
    (if (= detail :low)
      [trunk (cone radius (* height 0.86) 6 (* height 0.14))]
      [trunk
       (cone radius (* height 0.52) 10 (* height 0.12))
       (cone (* radius 0.76) (* height 0.48) 10 (* height 0.36))
       (cone (* radius 0.52) (* height 0.40) 10 (* height 0.60))])))

(defn- shrub [{:keys [width depth height seed]} detail]
  (let [radius (min (* width 0.3) (* depth 0.3) (* height 0.5))]
    (if (= detail :low)
      [(sphere-part radius [0.0 radius 0.0] 3 5)]
      [(sphere-part radius [0.0 radius 0.0] 5 8)
       (sphere-part (* radius 0.72) [(seed-offset seed 5 (* width 0.28)) (* radius 0.85) 0.0] 4 7)
       (sphere-part (* radius 0.68) [0.0 (* radius 0.8) (seed-offset seed 6 (* depth 0.26))] 4 7)])))

(defn vegetation-mesh
  "Return `[positions normals uvs indices]` for a deterministic vegetation LOD."
  ([spec] (vegetation-mesh spec :high))
  ([{:keys [variant] :as spec} detail]
   (let [spec (assoc spec :seed (or (:seed spec) 0))]
     (validate! spec detail)
     (combine (case variant
                :broadleaf (broadleaf spec detail)
                :conifer (conifer spec detail)
                :shrub (shrub spec detail))))))

(defn bounds [{:keys [width depth height]}]
  {:min [(- (/ width 2.0)) 0.0 (- (/ depth 2.0))]
   :max [(/ width 2.0) height (/ depth 2.0)]})

(defn vegetation-lods [spec]
  (mapv (fn [[detail min-pixels]]
          (let [[_ _ _ indices :as generated] (vegetation-mesh spec detail)]
            {:id detail :min-pixels min-pixels :mesh generated
             :triangle-count (quot (count indices) 3) :bounds (bounds spec)}))
        [[:high 64.0] [:low 0.0]]))

(defn webgpu-registration
  "Create a geometry registry accepted by WebGPU's generic `:mesh` spec.
   Keys are `<id>-high` and `<id>-low`; values retain bounds for culling/QC."
  [registration-id spec]
  (into {}
        (for [{detail :id :keys [mesh bounds triangle-count]} (vegetation-lods spec)
              :let [[positions normals _uvs indices] mesh
                    key (keyword (str (name registration-id) "-" (name detail)))]]
          [key {:type :mesh
                :mesh {:positions (mapv vec (partition 3 positions))
                       :normals (mapv vec (partition 3 normals))
                       :indices indices}
                :bounds bounds :triangle-count triangle-count}])))
