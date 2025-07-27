{ pkgs ? import <nixpkgs> {} }:

pkgs.mkShell {
  packages = with pkgs; [
    babashka
    bubblewrap
    strace
  ];

  shellHook = ''
    exec bwrap \
      --unshare-all --share-net \
      --ro-bind /nix /nix \
      --ro-bind /etc /etc \
      --bind $PWD $PWD \
      --bind $HOME/.clojure $HOME/.clojure \
      --bind $HOME/.m2 $HOME/.m2 \
      --proc /proc \
      --dev /dev \
      --tmpfs /tmp bash
  '';
}
