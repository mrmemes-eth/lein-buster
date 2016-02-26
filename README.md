# lein-buster

A Leiningen plugin to generate fingerprinted files suitable for use in browser
cache-busting.

It was designed with the specific usecase of fingerprinting CLJS files that are
bundled as part of an uberjar alongside other clojure code in mind.
`lein-buster` provides either auto-run hooks that invoke it after
`leiningen.compile` or a task to invoke it manually from a project.

Once run, `lein-buster` will output two types of artifacts:

1. A fingerprinted version of your specified files in the format of
   `<filename>-<fingerprint>.<extension>`.
2. A manifest file in `JSON` format so that you can map the filename you know to
   the generated one.

## Configuration

Buster needs to know which files you want to fingerprint and where to write a
manifest to. You can supply this configuration inside a `:buster` map like so:


```clojure
:buster {:files ["resources/public/scripts/awesome.js"]
         :manifest "resources/manifest.json"}
```

If you're not feeling picky, you can stick that configuration at the
`project.clj`'s top level. Otherwise, you can put it at the top level of a
relevant profile (example below).

Buster also supplies a hook to run itself post-compile with. You can add that to
your `project.clj` like so:

```clojure
:hooks [leiningen.buster]
```

If you're using a hook from another plugin that generates the static files you
want fingerprinted (e.g. [`lein-cljsbuild`] [lein-cljsbuild]), you should make
sure that hook comes before buster's:

```clojure
:hooks [leiningen.cljsbuild leiningen.buster]
```

## Usage

Add `[lein-buster "0.1.0"]` to the `:plugins` vector of your `project.clj`.

You can invoke `lein-buster` manually from your project like so:

    $ lein buster

This is probably not that useful, outside of making sure you've configured
things properly. The auto-run configuration outlined above is probably closer to
what you're looking for.

If you want to have `lein-buster` run only as part of your jar compilation
(which is likely), your buster config in your `project.clj` can be nested under
the relevant profile like so:

```clojure
:profiles {:uberjar {:hooks [leiningen.buster]
                     :buster {:files ["resources/public/scripts/awesome.js"]
                              :manifest "resources/manifest.json"}}}
```

## Integrating

You'll want to parse the generated manifest file and expose the mappings to your
views so that you can substitute the file name you know (e.g. `application.js`)
to the fingerprinted version (e.g. `application-acbd18db4c.js`).

In my current project, we use [`cheshire`][chesh] to parse the JSON from clojure
and construct our views using [`selmer`][selmer]. We added a custom `selmer` tag
to help with this translation:

```clojure
(defn load-revision-manifest
  [path]
  (when-let [manifest (io/resource path)]
    (-> manifest slurp chesh/parse-string)))

(def revision-manifest
  (memoize load-revision-manifest))

;; Returns the fingerprinted asset name for a file listed in the revision manifest.
(selmer/add-tag! :asset-name
                 (fn [args context-map]
                   (let [asset (first args)
                         manifest (revision-manifest "manifest.json")]
                     (or (get manifest asset) asset))))
```

Subsequent use in a Selmer template looks like this:

```html
<script type="text/javascript" src="/scripts/{% asset-name application.js %}"></script>

```

If you're using a different templating engine or a completely different
approach, please add how you're integrating `lein-buster` to the wiki!

## License

Copyright © 2016 Stephen Caudill

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.


[lein-cljsbuild]: https://github.com/emezeske/lein-cljsbuild
[chesh]: https://github.com/dakrone/cheshire
[selmer]: https://github.com/yogthos/Selmer
