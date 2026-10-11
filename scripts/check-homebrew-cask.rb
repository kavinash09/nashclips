# Run with: brew ruby scripts/check-homebrew-cask.rb dist/homebrew-preview
# Loads the cask DSL without downloading, installing, launching, or uninstalling.
require "json"
require "tmpdir"
require "fileutils"
require "digest"
require "cask/cask_loader"

abort "Usage: brew ruby scripts/check-homebrew-cask.rb PACKAGE_DIRECTORY" unless ARGV.length == 1
directory = Pathname.new(ARGV.fetch(0)).expand_path
metadata = JSON.parse((directory / "package.json").read)
raise "Invalid preview flag" unless [true, false].include?(metadata.fetch("preview"))
raise "Invalid package version" unless metadata.fetch("version").is_a?(String) && metadata["version"].match?(/\A\d+(?:\.\d+){0,2}\z/)
architecture = metadata.fetch("architecture")
expected_arches = {
  "arm64" => [{ type: :arm, bits: 64 }],
  "x86_64" => [{ type: :intel, bits: 64 }],
  "universal" => nil,
}
raise "Invalid architecture" unless expected_arches.key?(architecture)
suffix = metadata["preview"] ? "-preview" : ""
expected_archive = "nashclip-#{metadata['version']}-macos-#{architecture}#{suffix}.zip"
raise "Archive filename mismatch" unless metadata.fetch("archive") == expected_archive
source = directory / "Casks" / (metadata.fetch("preview") ? "nashclip.rb.example" : "nashclip.rb")
archive = directory / metadata.fetch("archive")
raise "Archive checksum mismatch" unless Digest::SHA256.file(archive).hexdigest == metadata.fetch("sha256")

Dir.mktmpdir("nashclip-cask-check-") do |temporary|
  # CaskLoader requires a .rb filename; keep the preview draft non-installable.
  path = Pathname.new(temporary) / "nashclip.rb"
  FileUtils.cp(source, path)
  cask = Cask::CaskLoader.load(path)
  raise "Wrong cask token" unless cask.token == "nashclip"
  raise "Version mismatch" unless cask.version.to_s == metadata.fetch("version")
  raise "Cask checksum mismatch" unless cask.sha256.to_s == metadata.fetch("sha256")
  raise "Download URL mismatch" unless cask.url.to_s == metadata.fetch("cask_url")
  raise "Homepage mismatch" unless cask.homepage == metadata.fetch("homepage")
  depends = cask.depends_on
  raise "Architecture mismatch" unless depends.arch == expected_arches.fetch(architecture)
  raise "Minimum macOS mismatch" unless depends.macos&.to_h == { ">=" => ["13"] }
  unless depends.maximum_macos.nil? && depends.linux.nil? && depends.formula.empty? && depends.cask.empty?
    raise "Unexpected dependency or OS restriction"
  end
  artifacts = cask.artifacts.map { |artifact| [artifact.class.dsl_key, artifact.to_args] }
  expected_artifacts = { app: ["nashclip.app"], uninstall: [{ quit: "dev.nashclip.native" }] }
  unless artifacts.length == 2 && artifacts.to_h == expected_artifacts
    raise "Unexpected install/uninstall artifacts (only the app and normal quit are allowed)"
  end
  raise "Cask is disabled or deprecated" if cask.disabled? || cask.deprecated?
end
puts "PASS: Homebrew cask matches package version, checksum, URLs, platform, app, and quit-only uninstall (no installation)"
