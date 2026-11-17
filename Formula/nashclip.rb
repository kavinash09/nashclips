class Nashclip < Formula
  desc "Local macOS clipboard history with a sliding drawer"
  homepage "https://github.com/kavinash09/nashclips"
  url "https://github.com/kavinash09/nashclips/releases/download/v1.0.1/nashclip-1.0.1-source.tar.gz"
  version "1.0.1"
  sha256 "de275027f6118db2ce22d1fcb1f0f067d57bb2b6e2445bbed1bf92497947ef03"
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
      This source release builds the Swift app locally using Apple's Command Line Tools.
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
    bundle_version = Utils.safe_popen_read("/usr/bin/plutil", "-extract", "CFBundleShortVersionString",
                                          "raw", "-o", "-", app/"Contents/Info.plist").strip
    assert_equal version.to_s, bundle_version
    system "/usr/bin/codesign", "--verify", "--deep", "--strict", app
  end
end
