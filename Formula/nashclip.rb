class Nashclip < Formula
  desc "Local macOS clipboard history with a sliding drawer"
  homepage "https://github.com/kavinash09/nashclips"
  url "https://github.com/kavinash09/nashclips/archive/e4da8e80dc33c5ec1d081f8297c40c5574f08467.tar.gz"
  version "1.0-preview.1"
  sha256 "6e30a9854d9f27851f5b524910f0d55de7a4eb8177a00baa0f88cb8691fa956d"
  license "Apache-2.0"

  depends_on macos: :ventura

  def install
    system "/bin/sh", "nashclip-swift/build.sh"
    libexec.install "nashclip-swift/build/nashclip.app"
    (bin/"nashclip").write <<~SH
      #!/bin/sh
      exec /usr/bin/open "#{opt_libexec}/nashclip.app" "$@"
    SH
  end

  def caveats
    <<~EOS
      This preview builds the Swift app locally using Apple's Command Line Tools.
      Launch with: nashclip

      Quit other nashclip variants before launching or upgrading.
      Allow nashclip in System Settings > Privacy & Security > Accessibility
      for automatic paste. Local builds are ad hoc signed; updates may require
      renewing Accessibility permission. Real paste acceptance remains pending.

      Clipboard history is plaintext in ~/.nashclip. Pause before copying secrets.
      Uninstalling preserves history and settings.
    EOS
  end

  test do
    app = libexec/"nashclip.app"
    assert_path_exists app/"Contents/MacOS/nashclip"
    assert_predicate app/"Contents/MacOS/nashclip", :executable?
    assert_path_exists app/"Contents/Resources/LICENSE"
    assert_path_exists app/"Contents/Resources/NOTICE"
    system "/usr/bin/codesign", "--verify", "--deep", "--strict", app
  end
end
