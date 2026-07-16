(ns kotoba.render.character
  "Portable procedural combat-character silhouettes with high/low LODs.

   Meshes are static today, but registration retains stable joint and socket
   metadata so a skinned/segmented adapter can animate the same authored rig."
  (:require [kotoba.render.mesh :as mesh]))

(def details #{:high :low})

(def joint-order [:root :arm-left :arm-right :leg-left :leg-right])

(defn- vertex-joint [{:keys [width height]} [x y _]]
  (cond
    (and (< y (* height 0.44)) (neg? x)) 3
    (< y (* height 0.44)) 4
    (and (< y (* height 0.78)) (< x (* width -0.27))) 1
    (and (< y (* height 0.78)) (> x (* width 0.27))) 2
    :else 0))

(defn skinning-attributes
  "Four-lane glTF-compatible joint/weight streams for a generated operator mesh.
   The silhouette is segmented at anatomical part boundaries, so its one-hot
   weights deliberately produce rigid skinning."
  [spec positions]
  (let [joints (mapv #(vector (vertex-joint spec %) 0 0 0) positions)]
    {:joints joints
     :weights (mapv (fn [_] [1.0 0.0 0.0 0.0]) joints)
     :joint-order joint-order}))

(defn- m4-rotate-x-around [[_ py pz] angle]
  (let [c (#?(:clj Math/cos :cljs js/Math.cos) angle)
        s (#?(:clj Math/sin :cljs js/Math.sin) angle)]
    [1.0 0.0 0.0 0.0
     0.0 c s 0.0
     0.0 (- s) c 0.0
     0.0 (+ (* (- 1.0 c) py) (* s pz))
     (+ (* (- s) py) (* (- 1.0 c) pz)) 1.0]))

(defn walk-palette
  "Ordered column-major skin matrices for `joint-order`. `phase` is cycles and
   `amount` is 0..1. Opposing arms and legs swing about authored pivots."
  [{:keys [width height]} phase amount]
  (let [pi #?(:clj Math/PI :cljs js/Math.PI)
        wave (* (#?(:clj Math/sin :cljs js/Math.sin) (* 2.0 pi phase))
                (min 1.0 (max 0.0 amount)))
        arm (* 0.72 wave) leg (* 0.58 wave)
        pivot (fn [x y] [(* width x) (* height y) 0.0])]
    [[1.0 0.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 1.0]
     (m4-rotate-x-around (pivot -0.39 0.71) arm)
     (m4-rotate-x-around (pivot 0.39 0.71) (- arm))
     (m4-rotate-x-around (pivot -0.19 0.43) (- leg))
     (m4-rotate-x-around (pivot 0.19 0.43) leg)]))

(defn- combine [meshes]
  (reduce (fn [[ps ns us is] [p n u idx]]
            (let [base (quot (count ps) 3)]
              [(into ps p) (into ns n) (into us u) (into is (map #(+ base %) idx))]))
          [[] [] [] []] meshes))

(defn- assemble
  "Combine `[material-role mesh]` parts and retain indexed triangle ranges.
   Ranges are metadata rather than extra draw calls, keeping the current single
   skinned-draw budget while allowing a material-array backend to shade authored
   armour/skin/fabric/weapon roles without rebuilding geometry."
  [parts]
  (loop [remaining parts mesh [[][] [] [] []] ranges []]
    (if-let [[role part] (first remaining)]
      (let [[ps ns us is] mesh
            [p n u idx] part
            base (quot (count ps) 3)
            index-start (count is)
            index-count (count idx)]
        (recur (next remaining)
               [(into ps p) (into ns n) (into us u)
                (into is (map #(+ base %) idx))]
               (conj ranges {:role role :index-start index-start
                             :index-count index-count})))
      {:mesh mesh :material-ranges ranges})))

(defn- transform [[positions normals uvs indices] [sx sy sz] [tx ty tz]]
  (let [positions' (vec (mapcat (fn [[x y z]]
                                  [(+ tx (* sx x)) (+ ty (* sy y)) (+ tz (* sz z))])
                                (partition 3 positions)))
        normals' (vec (mapcat (fn [[x y z]]
                                (let [nx (/ x sx) ny (/ y sy) nz (/ z sz)
                                      length (#?(:clj Math/sqrt :cljs js/Math.sqrt)
                                              (+ (* nx nx) (* ny ny) (* nz nz)))]
                                  (if (pos? length)
                                    [(/ nx length) (/ ny length) (/ nz length)]
                                    [0.0 1.0 0.0])))
                              (partition 3 normals)))]
    [positions' normals' uvs indices]))

(defn- box [size center] (transform (mesh/cube) size center))
(defn- sphere [size center stacks slices]
  (transform (mesh/sphere stacks slices) size center))
(defn- limb [radius height center sectors]
  (transform (mesh/cylinder-pipe radius 0.0 height sectors)
             [1.0 1.0 1.0] center))

(defn rig-metadata
  "Stable normalized joints/sockets. Coordinates scale with the registered mesh."
  [{:keys [width depth height weapon-side] :or {weapon-side :right}}]
  (let [side (if (= weapon-side :left) -1.0 1.0)]
    {:schema :kotoba.render/character-rig-v1
     :joints {:root [0.0 0.0 0.0]
              :hips [0.0 (* height 0.47) 0.0]
              :spine [0.0 (* height 0.66) 0.0]
              :neck [0.0 (* height 0.84) 0.0]
              :head [0.0 (* height 0.91) 0.0]
              :hand-left [(* width -0.50) (* height 0.58) 0.0]
              :hand-right [(* width 0.50) (* height 0.58) 0.0]
              :foot-left [(* width -0.19) 0.0 0.0]
              :foot-right [(* width 0.19) 0.0 0.0]}
     :sockets {:weapon-hand [(* side width 0.50) (* height 0.58) 0.0]
               :weapon-muzzle [(* side width 0.36) (* height 0.61) (* depth 0.72)]
               :back [0.0 (* height 0.70) (* depth -0.42)]}}))

(defn character-assembly
  "Authored procedural combat operator and rifle assembly.

   High LOD has independently readable armour, helmet, backpack, hands, boots,
   and a rifle made from stock/receiver/magazine/handguard/barrel/sight/muzzle.
   Low LOD preserves the same body/weapon silhouette and material roles with a
   deliberately reduced primitive and radial-segment budget."
  ([spec] (character-assembly spec :high))
  ([{:keys [width depth height weapon-side]
     :or {weapon-side :right} :as spec} detail]
   (when-not (details detail)
     (throw (ex-info "unsupported character detail" {:detail detail :supported details})))
   (when-not (and (pos? width) (pos? depth) (pos? height))
     (throw (ex-info "character dimensions must be positive" (select-keys spec [:width :depth :height]))))
   (let [high? (= detail :high)
         sectors (if high? 8 5)
         side (if (= weapon-side :left) -1.0 1.0)
         xw (* side width)
         body [[:fabric (box [(* width 0.50) (* height 0.31) (* depth 0.52)]
                             [0.0 (* height 0.63) 0.0])]
               [:fabric (box [(* width 0.42) (* height 0.12) (* depth 0.46)]
                             [0.0 (* height 0.43) 0.0])]
               [:skin (sphere [(* width 0.30) (* height 0.15) (* depth 0.33)]
                              [0.0 (* height 0.88) 0.0]
                              (if high? 7 4) (if high? 10 6))]
               [:fabric (limb (* width 0.095) (* height 0.34)
                              [(* width -0.38) (* height 0.55) 0.0] sectors)]
               [:fabric (limb (* width 0.095) (* height 0.34)
                              [(* width 0.38) (* height 0.55) 0.0] sectors)]
               [:fabric (limb (* width 0.115) (* height 0.40)
                              [(* width -0.18) (* height 0.20) 0.0] sectors)]
               [:fabric (limb (* width 0.115) (* height 0.40)
                              [(* width 0.18) (* height 0.20) 0.0] sectors)]
               ;; boots and gloves sharpen extremities even in the low LOD.
               [:armour (box [(* width 0.25) (* height 0.09) (* depth 0.52)]
                             [(* width -0.18) (* height 0.045) (* depth 0.08)])]
               [:armour (box [(* width 0.25) (* height 0.09) (* depth 0.52)]
                             [(* width 0.18) (* height 0.045) (* depth 0.08)])]
               [:armour (box [(* width 0.16) (* height 0.10) (* depth 0.22)]
                             [(* width -0.38) (* height 0.40) (* depth 0.05)])]
               [:armour (box [(* width 0.16) (* height 0.10) (* depth 0.22)]
                             [(* width 0.38) (* height 0.40) (* depth 0.05)])]]
         weapon [[:weapon (box [(* width 0.14) (* height 0.12) (* depth 0.35)]
                               [(* xw 0.27) (* height 0.60) (* depth -0.30)])]
                 [:weapon (box [(* width 0.18) (* height 0.14) (* depth 0.34)]
                               [(* xw 0.34) (* height 0.61) (* depth 0.03)])]
                 [:weapon (box [(* width 0.14) (* height 0.11) (* depth 0.42)]
                               [(* xw 0.42) (* height 0.61) (* depth 0.40)])]
                 [:weapon (box [(* width 0.065) (* height 0.065) (* depth 0.42)]
                               [(* xw 0.52) (* height 0.62) (* depth 0.81)])]
                 [:weapon-accent (box [(* width 0.12) (* height 0.20) (* depth 0.15)]
                                      [(* xw 0.35) (* height 0.50) (* depth 0.08)])]]
         detail-parts
         (when high?
           [[:armour (box [(* width 0.58) (* height 0.24) (* depth 0.64)]
                          [0.0 (* height 0.69) (* depth 0.01)])]
            [:armour-accent (box [(* width 0.26) (* height 0.13) (* depth 0.67)]
                                 [(* width -0.34) (* height 0.76) 0.0])]
            [:armour-accent (box [(* width 0.26) (* height 0.13) (* depth 0.67)]
                                 [(* width 0.34) (* height 0.76) 0.0])]
            ;; helmet shell + forward visor + rear backpack establish a combat read.
            [:armour (sphere [(* width 0.35) (* height 0.17) (* depth 0.38)]
                             [0.0 (* height 0.94) (* depth -0.01)] 5 10)]
            [:visor (box [(* width 0.46) (* height 0.075) (* depth 0.12)]
                         [0.0 (* height 0.91) (* depth 0.32)])]
            [:fabric (box [(* width 0.40) (* height 0.30) (* depth 0.24)]
                          [0.0 (* height 0.65) (* depth -0.38)])]
            [:armour-accent (box [(* width 0.10) (* height 0.20) (* depth 0.12)]
                                 [(* width -0.17) (* height 0.67) (* depth -0.52)])]
            [:armour-accent (box [(* width 0.10) (* height 0.20) (* depth 0.12)]
                                 [(* width 0.17) (* height 0.67) (* depth -0.52)])]
            ;; rifle readability: raised optic, muzzle brake and side rail.
            [:weapon-accent (box [(* width 0.10) (* height 0.08) (* depth 0.15)]
                                 [(* xw 0.37) (* height 0.72) (* depth 0.16)])]
            [:weapon-accent (box [(* width 0.11) (* height 0.09) (* depth 0.10)]
                                 [(* xw 0.56) (* height 0.62) (* depth 1.04)])]
            [:weapon-accent (box [(* width 0.035) (* height 0.035) (* depth 0.36)]
                                 [(+ (* xw 0.42) (* side width 0.08))
                                  (* height 0.62) (* depth 0.40)])]])]
     (assemble (vec (concat body weapon detail-parts))))))

(defn character-mesh
  "Return `[positions normals uvs indices]` for a grounded combat operator."
  ([spec] (character-mesh spec :high))
  ([spec detail] (:mesh (character-assembly spec detail))))

(defn bounds [{:keys [width depth height]}]
  {:min [(* width -0.64) 0.0 (* depth -0.66)]
   :max [(* width 0.64) (* height 1.11) (* depth 1.10)]})

(defn character-lods [spec]
  (mapv (fn [[detail min-pixels]]
          (let [{generated :mesh ranges :material-ranges}
                (character-assembly spec detail)
                [_ _ _ indices] generated]
            {:id detail :min-pixels min-pixels :mesh generated
             :triangle-count (quot (count indices) 3)
             :bounds (bounds spec) :rig (rig-metadata spec)
             :material-ranges ranges}))
        [[:high 72.0] [:low 0.0]]))

(defn webgpu-registration [registration-id spec]
  (into {}
        (for [{detail :id :keys [mesh bounds triangle-count rig material-ranges]} (character-lods spec)
              :let [[positions normals uvs indices] mesh
                    positions3 (mapv vec (partition 3 positions))
                    skin (skinning-attributes spec positions3)
                    key (keyword (str (name registration-id) "-" (name detail)))]]
          [key {:type :mesh
                :mesh {:positions positions3
                       :normals (mapv vec (partition 3 normals))
                       :uvs (mapv vec (partition 2 uvs)) :indices indices
                       :joints (:joints skin) :weights (:weights skin)
                       :material-ranges material-ranges}
                :bounds bounds :triangle-count triangle-count :rig rig}])))
