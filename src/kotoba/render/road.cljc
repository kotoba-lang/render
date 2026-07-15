(ns kotoba.render.road
  "Terrain-following, seam-free road ribbons over kotoba.render.terrain heightfields."
  (:require [kotoba.render.terrain :as terrain]))

(def details [:high :medium :low])
(def detail-divisor {:high 1 :medium 2 :low 4})

(defn- sqrt [x] (#?(:clj Math/sqrt :cljs js/Math.sqrt) x))
(defn- floor [x] (#?(:clj Math/floor :cljs js/Math.floor) x))

(defn terrain-height
  "Bilinearly sample the canonical terrain grid at world `[x z]`. This deliberately
   delegates every corner sample to terrain/height-at, preserving its seed/patch SSoT."
  [{:keys [size base-segments] :as terrain-spec} x z]
  (let [cell (/ size base-segments)
        gx (/ x cell) gz (/ z cell)
        x0 (long (floor gx)) z0 (long (floor gz))
        tx (- gx x0) tz (- gz z0)
        h00 (terrain/height-at terrain-spec x0 z0)
        h10 (terrain/height-at terrain-spec (inc x0) z0)
        h01 (terrain/height-at terrain-spec x0 (inc z0))
        h11 (terrain/height-at terrain-spec (inc x0) (inc z0))]
    (+ (* (- 1.0 tx) (- 1.0 tz) h00)
       (* tx (- 1.0 tz) h10)
       (* (- 1.0 tx) tz h01)
       (* tx tz h11))))

(defn- validate! [{:keys [path width shoulder camber shoulder-drop clearance
                           uv-scale base-subdivisions miter-limit terrain]} detail]
  (when-not (contains? detail-divisor detail)
    (throw (ex-info "unsupported road detail" {:detail detail :supported details})))
  (when-not (and (vector? path) (<= 2 (count path))
                 (every? #(and (= 2 (count %)) (every? number? %)) path)
                 (every? (fn [[[ax az] [bx bz]]]
                           (pos? (+ (* (- bx ax) (- bx ax)) (* (- bz az) (- bz az)))))
                         (partition 2 1 path)))
    (throw (ex-info "road path requires distinct world-space [x z] points" {:path path})))
  (when-not (and (number? width) (pos? width) (number? shoulder) (not (neg? shoulder))
                 (number? camber) (not (neg? camber))
                 (number? shoulder-drop) (not (neg? shoulder-drop))
                 (number? clearance) (number? uv-scale) (pos? uv-scale)
                 (number? miter-limit) (>= miter-limit 1.0)
                 (integer? base-subdivisions) (pos? base-subdivisions)
                 (zero? (mod base-subdivisions 4)))
    (throw (ex-info "invalid road cross-section or tessellation"
                    {:width width :shoulder shoulder :camber camber
                     :shoulder-drop shoulder-drop :clearance clearance
                     :uv-scale uv-scale :base-subdivisions base-subdivisions
                     :miter-limit miter-limit})))
  (when-not (and (map? terrain) (number? (:size terrain)) (pos? (:size terrain))
                 (integer? (:base-segments terrain)) (pos? (:base-segments terrain))
                 (number? (:amplitude terrain)) (not (neg? (:amplitude terrain)))
                 (integer? (:seed terrain)) (<= 0 (:seed terrain) 4294967295))
    (throw (ex-info "road requires a terrain heightfield spec" {:terrain terrain}))))

(defn- distance [[ax az] [bx bz]]
  (sqrt (+ (* (- bx ax) (- bx ax)) (* (- bz az) (- bz az)))))

(defn- centerline [path subdivisions]
  (vec
   (mapcat (fn [segment-index [[ax az] [bx bz]]]
             (for [step (range (inc subdivisions))
                   :when (or (zero? segment-index) (pos? step))
                   :let [t (/ step (double subdivisions))]]
               [(+ ax (* (- bx ax) t)) (+ az (* (- bz az) t))]))
           (range) (partition 2 1 path))))

(defn- unit-direction [[ax az] [bx bz]]
  (let [dx (- bx ax) dz (- bz az) length (distance [ax az] [bx bz])]
    [(/ dx length) (/ dz length)]))

(defn- lateral-frame
  "Return `[lateral-x lateral-z miter-scale]`. Interior rows use the normalized
   sum of adjacent segment normals. The reciprocal projection preserves authored
   width through the turn; miter-limit bounds spikes at acute/reversing corners."
  [centers i miter-limit]
  (let [last-index (dec (count centers))
        prev-dir (if (zero? i)
                   (unit-direction (nth centers 0) (nth centers 1))
                   (unit-direction (nth centers (dec i)) (nth centers i)))
        next-dir (if (= i last-index)
                   prev-dir
                   (unit-direction (nth centers i) (nth centers (inc i))))
        [pnx pnz] [(- (second prev-dir)) (first prev-dir)]
        [nnx nnz] [(- (second next-dir)) (first next-dir)]
        mx (+ pnx nnx) mz (+ pnz nnz)
        magnitude (sqrt (+ (* mx mx) (* mz mz)))
        [lx lz] (if (< magnitude 1.0e-9) [nnx nnz] [(/ mx magnitude) (/ mz magnitude)])
        projection (#?(:clj Math/abs :cljs js/Math.abs) (+ (* lx nnx) (* lz nnz)))
        scale (min miter-limit (/ 1.0 (max projection 1.0e-6)))]
    [lx lz scale]))

(defn road-mesh
  "Bake a continuous terrain-following ribbon as `[positions normals uvs indices]`.

   The five fixed cross-section columns are outer shoulder, carriageway edge,
   crown, opposite edge, outer shoulder. Segment joins share one centerline row;
   cumulative path distance gives continuous UVs across every join."
  ([spec] (road-mesh spec :high))
  ([spec detail]
   (let [spec (merge {:width 8.0 :shoulder 1.5 :camber 0.12 :shoulder-drop 0.08
                      :clearance 0.03 :uv-scale 8.0 :base-subdivisions 8
                      :miter-limit 2.0} spec)
         _ (validate! spec detail)
         {:keys [path width shoulder camber shoulder-drop clearance uv-scale terrain miter-limit]} spec
         subdivisions (quot (:base-subdivisions spec) (detail-divisor detail))
         centers (centerline path subdivisions)
         distances (vec (reductions + 0.0 (map distance centers (rest centers))))
         half-width (/ width 2.0)
         columns [[(- (+ half-width shoulder)) (- shoulder-drop)]
                  [(- half-width) 0.0] [0.0 camber]
                  [half-width 0.0] [(+ half-width shoulder) (- shoulder-drop)]]
         rows (mapv (fn [i [cx cz]]
                      (let [[lx lz miter-scale] (lateral-frame centers i miter-limit)]
                        (mapv (fn [[offset crown]]
                                (let [offset (* offset miter-scale)
                                      x (+ cx (* lx offset)) z (+ cz (* lz offset))]
                                  [(double x)
                                   (+ (terrain-height terrain x z) clearance crown)
                                   (double z)]))
                              columns)))
                    (range) centers)
         row-width (count columns)
         positions3 (vec (mapcat identity rows))
         ;; Area-weighted normals over the same indexed surface keep camber and
         ;; heightfield slope visible without introducing duplicate seam vertices.
         indices (vec (mapcat (fn [row]
                               (mapcat (fn [col]
                                         (let [a (+ (* row row-width) col) b (inc a)
                                               c (+ a row-width) d (inc c)]
                                           [a c b b c d]))
                                       (range (dec row-width))))
                             (range (dec (count rows)))))
         uvs (vec (mapcat (fn [v]
                            (mapcat (fn [column]
                                      [(/ column (double (dec row-width))) (/ v uv-scale)])
                                    (range row-width)))
                          distances))
         normals (vec (mapcat (fn [row-index]
                                (let [prev-row (nth rows (max 0 (dec row-index)))
                                      next-row (nth rows (min (dec (count rows)) (inc row-index)))]
                                  (mapcat (fn [col]
                                            (let [[px py pz] (nth prev-row col)
                                                  [nx ny nz] (nth next-row col)
                                                  [lx ly lz] (nth (nth rows row-index) (max 0 (dec col)))
                                                  [rx ry rz] (nth (nth rows row-index) (min (dec row-width) (inc col)))
                                                  fx (- nx px) fy (- ny py) fz (- nz pz)
                                                  sx (- rx lx) sy (- ry ly) sz (- rz lz)
                                                  cx (- (* sy fz) (* sz fy))
                                                  cy (- (* sz fx) (* sx fz))
                                                  cz (- (* sx fy) (* sy fx))
                                                  length (sqrt (+ (* cx cx) (* cy cy) (* cz cz)))]
                                              [(/ cx length) (/ cy length) (/ cz length)]))
                                          (range row-width))))
                              (range (count rows))))]
     [(vec (mapcat identity positions3)) normals uvs indices])))

(defn- select-strip
  [[positions normals uvs _indices] row-width columns]
  (let [positions (vec (partition 3 positions))
        normals (vec (partition 3 normals))
        uvs (vec (partition 2 uvs))
        rows (quot (count positions) row-width)
        selected (vec (for [row (range rows) col columns] (+ (* row row-width) col)))
        strip-width (count columns)
        indices (vec (mapcat (fn [row]
                               (mapcat (fn [col]
                                         (let [a (+ (* row strip-width) col) b (inc a)
                                               c (+ a strip-width) d (inc c)]
                                           [a c b b c d]))
                                       (range (dec strip-width))))
                             (range (dec rows))))]
    [(vec (mapcat #(nth positions %) selected))
     (vec (mapcat #(nth normals %) selected))
     (vec (mapcat #(nth uvs %) selected))
     indices]))

(defn- combine-meshes [meshes]
  (reduce (fn [[ps ns us is] [p n u i]]
            (let [vertex-offset (quot (count ps) 3)]
              [(into ps p) (into ns n) (into us u) (into is (map #(+ vertex-offset %) i))]))
          [[] [] [] []] meshes))

(defn road-mesh-parts
  "Material-separable meshes derived from the exact same ribbon rows.
   `:surface` is the carriageway; `:shoulder` combines both soil/gravel strips.
   Shared boundary positions are copied from one source mesh and remain byte-equal."
  ([spec] (road-mesh-parts spec :high))
  ([spec detail]
   (let [mesh (road-mesh spec detail)]
     {:surface (select-strip mesh 5 [1 2 3])
      :shoulder (combine-meshes [(select-strip mesh 5 [0 1])
                                 (select-strip mesh 5 [3 4])])})))

(defn road-lods [spec]
  (mapv (fn [[detail min-pixels]]
          (let [[_ _ _ indices :as mesh] (road-mesh spec detail)]
            {:id detail :min-pixels min-pixels :mesh mesh
             :triangle-count (quot (count indices) 3)}))
        [[:high 128.0] [:medium 48.0] [:low 0.0]]))

(defn webgpu-registration [registration-id spec]
  (into {}
        (for [detail details
              [part mesh] (road-mesh-parts spec detail)
              :let [[positions normals uvs indices] mesh
                    key (keyword (str (name registration-id) "-" (name part) "-" (name detail)))]]
          [key {:type :mesh
                :mesh {:positions (mapv vec (partition 3 positions))
                       :normals (mapv vec (partition 3 normals))
                       :uvs (mapv vec (partition 2 uvs))
                       :indices indices}
                :material-part part
                :triangle-count (quot (count indices) 3)}])))
